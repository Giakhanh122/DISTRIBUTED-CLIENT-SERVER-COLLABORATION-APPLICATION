package client;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import protocols.FileTransferMeta;
import protocols.Json;
import protocols.OpCode;
import protocols.Packet;
import network.PacketChannel;
import protocols.PacketValidator;
import transfer.FileReceiver;
import transfer.FileSender;
import transfer.FileTransferSession;

final class BroadcastFileController implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final long WORKER_SHUTDOWN_TIMEOUT_MS = 1000;

    private record Offer(String fileId, String sender, String fileName, long fileSize, String checksum) {}
    @FunctionalInterface
    private interface TransferTask { void run() throws IOException; }

    private final ClientEvents events;
    private final ServerConnection server;
    private final String host;
    private final int port;
    private final String username;
    private final File downloadDir;
    private final LongSupplier nextOfferNumber;
    private final Object lifecycleLock = new Object();
    private final Map<String, Offer> offers = new HashMap<>();
    private final Map<String, String> offerIds = new HashMap<>();
    private final Set<Socket> sockets = new HashSet<>();
    private final Map<String, FileTransferSession> sessions = new HashMap<>();
    private final Set<Thread> workers = new HashSet<>();
    private final Set<String> activeIds = new HashSet<>();
    private volatile boolean closed;

    BroadcastFileController(ServerConnection server, String host, int port,
                            String username, LongSupplier nextOfferNumber, ClientEvents events) {
        this.events = events;
        this.server = server;
        this.host = host;
        this.port = port;
        this.username = username;
        this.downloadDir = new File("downloads/" + username);
        this.nextOfferNumber = nextOfferNumber;
    }

    void onOffer(Packet packet) throws IOException {
        String fileId = packet.string("fileId");
        FileTransferMeta meta = FileTransferMeta.fromJson(Json.object("transferId", fileId,
                "sender", packet.string("sender"), "receiver", username,
                "fileName", packet.string("fileName"), "fileSize", packet.longValue("fileSize"),
                "checksum", packet.string("checksum")));
        String number;
        synchronized (lifecycleLock) {
            if (closed) return;
            number = Long.toString(nextOfferNumber.getAsLong());
            offers.put(fileId, new Offer(fileId, meta.sender(), meta.fileName(), meta.fileSize(), meta.checksum()));
            offerIds.put(number, fileId);
            events.onFileOffer(new FileOffer(fileId, number, meta, packet.string("communityId"), true));
        }
    }

    void upload(File file, CommunityClient.Selection community) throws IOException {
        String id = java.util.UUID.randomUUID().toString();
        startWorker(id, "Broadcast file failed: ", () -> uploadFile(id, file, community));
    }

    boolean accept(String number) throws IOException {
        Offer offer = claim(number);
        if (offer == null) return false;
        events.onFileState(offer.fileId(), ClientEvents.FileState.TRANSFERRING, "Downloading from server");
        startWorker(offer.fileId(), "[BROADCAST DOWNLOAD ERROR] ", () -> downloadFile(offer));
        return true;
    }

    boolean reject(String number) throws IOException {
        Offer offer = claim(number);
        if (offer == null) return false;
        startWorker(offer.fileId(), "[BROADCAST] Reject failed: ", () -> {
            Packet response = server.request(Packet.json(OpCode.FILE_BROADCAST_DECISION,
                    Json.object("fileId", offer.fileId(), "decision", "REJECT")),
                    OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE);
            requireOpen();
            if (!response.status().equals("REJECTED")) throw new IOException(response.status());
            finish(offer.fileId(), ClientEvents.FileState.REJECTED, "Rejected");
        });
        return true;
    }

    private Offer claim(String number) throws IOException {
        synchronized (lifecycleLock) {
            if (closed) return null;
            String id = offerIds.getOrDefault(number, number);
            offerIds.values().removeIf(id::equals);
            return offers.remove(id);
        }
    }

    private void uploadFile(String id, File file, CommunityClient.Selection community) throws IOException {
        long size = file.length();
        String checksum = FileSender.checksum(file);
        if (file.length() != size) throw new IOException("File changed while preparing upload");
        synchronized (lifecycleLock) {
            requireOpen();
            events.onFileOffer(new FileOffer(id, null,
                    new FileTransferMeta(id, username, community.name(), file.getName(), size, checksum), community.id(), false));
            events.onFileState(id, ClientEvents.FileState.TRANSFERRING, "Uploading to community storage");
        }
        Packet response = request(Packet.json(OpCode.FILE_BROADCAST_UPLOAD_REQUEST,
                Json.object("communityId", community.id(), "fileName", file.getName(),
                        "fileSize", size, "checksum", checksum)), OpCode.FILE_BROADCAST_UPLOAD_RESPONSE);
        String fileId = response.string("fileId");
        Socket socket = openDataSocket();
        try (socket) {
            PacketChannel channel = new PacketChannel(socket);
            channel.send(Packet.json(OpCode.FILE_BROADCAST_UPLOAD_START,
                    Json.object("fileId", fileId, "uploadToken", response.string("uploadToken"))));
            FileTransferSession upload = trackSession(new FileTransferMeta(fileId, username, community.name(),
                    file.getName(), size, checksum), id);
            FileSender sender = new FileSender(file, fileId, channel, upload, null);
            sender.send();
            sender.awaitResult();
        } finally {
            forgetSocket(socket);
        }
        finish(id, ClientEvents.FileState.COMPLETED, file.getAbsolutePath());
    }

    private void downloadFile(Offer offer) throws IOException {
        Packet response = request(Packet.json(OpCode.FILE_BROADCAST_DECISION,
                Json.object("fileId", offer.fileId(), "decision", "ACCEPT")), OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE);
        Socket socket = openDataSocket();
        try (socket) {
            PacketChannel channel = new PacketChannel(socket);
            channel.send(Packet.json(OpCode.FILE_BROADCAST_DOWNLOAD_START,
                    Json.object("fileId", offer.fileId(), "downloadToken", response.string("downloadToken"))));
            FileTransferMeta meta = new FileTransferMeta(offer.fileId(), offer.sender(), username,
                    offer.fileName(), offer.fileSize(), offer.checksum());
            FileTransferSession download = trackSession(meta, offer.fileId());
            File saved = new FileReceiver(channel, downloadDir, meta, download, null).receive();
            finish(offer.fileId(), ClientEvents.FileState.COMPLETED, saved.getAbsolutePath());
        } finally {
            forgetSocket(socket);
        }
    }

    private FileTransferSession trackSession(FileTransferMeta meta, String id) throws IOException {
        synchronized (lifecycleLock) {
            requireOpen();
            FileTransferSession session = new FileTransferSession(meta);
            session.accept();
            sessions.put(id, session);
            return session;
        }
    }

    private Packet request(Packet packet, OpCode responseOp) throws IOException {
        requireOpen();
        Packet response = server.request(packet, responseOp);
        requireOpen();
        PacketValidator.requireSuccess(response);
        return response;
    }

    private Socket openDataSocket() throws IOException {
        Socket socket = new Socket();
        try {
            synchronized (lifecycleLock) {
                requireOpen();
                sockets.add(socket);
            }
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            return socket;
        } catch (IOException e) {
            forgetSocket(socket);
            try {
                socket.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    private void forgetSocket(Socket socket) {
        synchronized (lifecycleLock) { sockets.remove(socket); }
    }

    private void startWorker(String id, String errorPrefix, TransferTask task) throws IOException {
        synchronized (lifecycleLock) {
            requireOpen();
            activeIds.add(id);
            Thread worker = Thread.ofVirtual().unstarted(() -> {
                try {
                    requireOpen();
                    task.run();
                } catch (IOException | RuntimeException e) {
                    finish(id, ClientEvents.FileState.FAILED, e.getMessage());
                    if (!closed) System.err.println(errorPrefix + e.getMessage());
                } finally {
                    synchronized (lifecycleLock) {
                        workers.remove(Thread.currentThread());
                        sessions.remove(id);
                        activeIds.remove(id);
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
    }

    private void finish(String id, ClientEvents.FileState state, String detail) {
        synchronized (lifecycleLock) {
            if (activeIds.remove(id)) events.onFileState(id, state, detail);
        }
    }

    /** Called after ServerConnection closes and releases workers waiting for replies. */
    void awaitWorkers() {
        Set<Thread> finishing;
        synchronized (lifecycleLock) { finishing = Set.copyOf(workers); }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(WORKER_SHUTDOWN_TIMEOUT_MS);
        for (Thread worker : finishing) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            try {
                worker.join(java.time.Duration.ofNanos(remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void requireOpen() throws IOException {
        if (closed) throw new IOException("Broadcast transfers are closed");
    }

    @Override
    public void close() {
        Set<Socket> closing;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            sessions.values().forEach(FileTransferSession::cancel);
            Set<String> failed = new HashSet<>(offers.keySet());
            for (String id : activeIds) {
                FileTransferSession session = sessions.get(id);
                if (session == null || session.state() != FileTransferSession.State.COMPLETED) failed.add(id);
            }
            activeIds.removeAll(failed);
            failed.forEach(id -> events.onFileState(id, ClientEvents.FileState.FAILED, "Server connection closed"));
            offers.clear();
            offerIds.clear();
            closing = Set.copyOf(sockets);
            sockets.clear();
        }
        for (Socket socket : closing) {
            try { socket.close(); } catch (IOException e) {
                System.err.println("Cannot close broadcast transfer: " + e.getMessage());
            }
        }
        // A worker waiting on a management reply is released when the session closes
        // ServerConnection. Do not interrupt it here and abort logout's connection.
    }
}
