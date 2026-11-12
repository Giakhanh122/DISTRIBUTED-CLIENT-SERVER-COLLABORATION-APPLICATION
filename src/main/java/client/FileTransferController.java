package client;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import protocols.FileTransferMeta;
import protocols.OpCode;
import protocols.Json;
import protocols.Packet;
import protocols.PacketChannel;
import transfer.FileReceiver;
import transfer.FileSender;
import transfer.FileTransferManager;
import transfer.FileTransferSession;

public final class FileTransferController implements AutoCloseable {
    private static final int TRANSFER_TIMEOUT_MS = 30000;

    private final ClientEvents events;
    private final String username;
    private final int peerPort;
    private final File downloadDir;
    private final Map<String, PeerConnection> peers;
    private final FileTransferManager transferManager = new FileTransferManager();
    private final ConcurrentHashMap<String, Closeable> transferConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PeerConnection> transferPeers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> offerSequences = new ConcurrentHashMap<>();
    private final Map<String, String> incomingOfferIds = new HashMap<>();
    private final LongSupplier nextOfferNumber;
    private boolean closingTransfers;

    FileTransferController(String username, int peerPort, File downloadDir,
                           Map<String, PeerConnection> peers, LongSupplier nextOfferNumber, ClientEvents events) {
        this.events = events;
        this.nextOfferNumber = Objects.requireNonNull(nextOfferNumber);
        this.username = Objects.requireNonNull(username);
        this.peerPort = peerPort;
        this.downloadDir = Objects.requireNonNull(downloadDir);
        this.peers = Objects.requireNonNull(peers);
    }

    public synchronized void handleIncomingOffer(String peerUsername, PeerConnection peer, Packet packet) throws IOException {
        FileTransferMeta meta = FileTransferMeta.fromJson(packet.jsonBody());
        if (!meta.receiver().equals(username) || !meta.sender().equals(peerUsername)
                || meta.sender().equals(username)) {
            throw new IOException("File offer has invalid participants");
        }
        FileTransferSession session = new FileTransferSession(meta);
        registerTransfer(session, peer, null);
        offerSequences.put(meta.transferId(), packet.sequence());
        String offerNumber = Long.toString(nextOfferNumber.getAsLong());
        incomingOfferIds.put(offerNumber, meta.transferId());
        events.onFileOffer(meta.transferId(), meta, null, true);
        System.out.println("\n[FILE OFFER #" + offerNumber + "] " + meta.sender() + " wants to send '" + meta.fileName()
                + "' (" + meta.fileSize() + " bytes).");
        System.out.println("SHA-256: " + meta.checksum());
        System.out.println("Type '/accept " + offerNumber + "' or '/reject " + offerNumber + "'");
    }

    public synchronized void handleIncomingDecision(PeerConnection peer, Packet packet) throws IOException {
        String transferId = packet.string("transferId");
        String decision = packet.string("decision");
        if (!(decision.equals("ACCEPT") || decision.equals("REJECT"))) {
            throw new IOException("Invalid FILE_DECISION payload");
        }
        FileTransferSession session = transferManager.getSession(transferId).orElse(null);
        if (session == null || transferPeers.get(transferId) != peer
                || !session.meta().sender().equals(username)
                || session.state() != FileTransferSession.State.OFFERED
                || !Objects.equals(offerSequences.get(transferId), packet.replyTo())) {
            throw new IOException("Decision does not match an outgoing offer on this chat connection");
        }
        if (decision.equals("ACCEPT")) {
            File file = transferManager.getOutboundFile(session.transferId());
            session.accept();
            events.onFileState(transferId, ClientEvents.FileState.TRANSFERRING, "Accepted by " + session.meta().receiver());
            System.out.println("\n[FILE] Transfer '" + session.meta().fileName()
                    + "' was ACCEPTED by " + session.meta().receiver() + ".");
            Thread.startVirtualThread(() -> runP2PSend(file, session, peer));
        } else {
            session.reject();
            events.onFileState(transferId, ClientEvents.FileState.REJECTED, "Rejected by " + session.meta().receiver());
            finishTransfer(session);
            System.out.println("\n[FILE] Transfer '" + session.meta().fileName()
                    + "' was REJECTED by " + session.meta().receiver() + ".");
        }
    }

    private void runP2PSend(File file, FileTransferSession session, PeerConnection peer) {
        try (Socket socket = new Socket()) {
            trackTransfer(session, socket);
            socket.connect(peer.peerAddress(), 10000);
            socket.setSoTimeout(TRANSFER_TIMEOUT_MS);
            PacketChannel channel = new PacketChannel(socket);
            channel.send(Packet.json(OpCode.FILE_OFFER, session.meta().toJson()));
            FileSender sender = new FileSender(file, session.transferId(), channel, session, null);
            String checksum = sender.send();
            sender.awaitResult();
            events.onFileState(session.transferId(), ClientEvents.FileState.COMPLETED, file.getAbsolutePath());
            System.out.println("\n[P2P SENT] " + file.getName() + " completed! SHA-256: " + checksum);
        } catch (IOException | RuntimeException e) {
            session.fail();
            events.onFileState(session.transferId(), ClientEvents.FileState.FAILED, e.getMessage());
            System.err.println("\n[P2P SEND ERROR] " + e.getMessage());
        } finally {
            finishTransfer(session);
        }
    }

    public void offer(PeerConnection peer, String receiver, File file) {
        if (receiver.equals(username)) {
            throw new IllegalArgumentException("Cannot send a file to yourself");
        }
        if (peer == null || peer.isClosed()) {
            System.out.println("Connect to " + receiver + " with /lookup first");
            return;
        }
        // Hashing can take time; keep both the console and the chat reader responsive.
        Thread.startVirtualThread(() -> {
            FileTransferSession session = null;
            try {
                long size = file.length();
                String checksum = FileSender.checksum(file);
                if (file.length() != size) {
                    throw new IOException("File changed while preparing offer");
                }
                FileTransferMeta meta = FileTransferMeta.create(username, receiver, file.getName(), size, checksum);
                session = new FileTransferSession(meta);
                synchronized (this) {
                    registerTransfer(session, peer, file);
                    events.onFileOffer(meta.transferId(), meta, null, false);
                }
                peer.sendPacket(Packet.json(OpCode.FILE_OFFER, meta.toJson()),
                        sequence -> recordOfferSequence(meta.transferId(), sequence));
                System.out.println("[FILE] Offer sent to " + receiver + ": '" + file.getName() + "'.");
            } catch (IOException | RuntimeException e) {
                if (session != null) {
                    session.fail();
                    events.onFileState(session.transferId(), ClientEvents.FileState.FAILED, e.getMessage());
                    finishTransfer(session);
                }
                if (session == null) events.onFileState("", ClientEvents.FileState.FAILED, e.getMessage());
                System.err.println("[FILE OFFER ERROR] " + e.getMessage());
            }
        });
    }

    public void sendDecision(String offerNumber, String decision) throws IOException {
        if (!decision.equals("ACCEPT") && !decision.equals("REJECT")) {
            throw new IllegalArgumentException("Invalid file decision");
        }
        String transferId;
        FileTransferSession session;
        PeerConnection peer;
        Packet response;
        synchronized (this) {
            transferId = incomingOfferIds.getOrDefault(offerNumber, offerNumber);
            session = transferManager.getSession(transferId).orElseThrow(
                    () -> new IOException("Offer not found: " + offerNumber));
            if (!session.meta().receiver().equals(username)
                    || session.state() != FileTransferSession.State.OFFERED) {
                throw new IOException("This is not a pending incoming offer");
            }
            peer = transferPeers.get(transferId);
            response = Packet.reply(OpCode.FILE_DECISION, offerSequences.get(transferId),
                    Json.object("transferId", transferId, "decision", decision));
            if (decision.equals("ACCEPT")) session.accept();
            else session.reject();
            events.onFileState(transferId, decision.equals("REJECT") ? ClientEvents.FileState.REJECTED : ClientEvents.FileState.TRANSFERRING, decision);
        }
        try {
            peer.sendPacket(response);
        } catch (IOException | RuntimeException e) {
            session.fail();
            events.onFileState(transferId, ClientEvents.FileState.FAILED, e.getMessage());
            finishTransfer(session);
            throw e;
        }
        if (decision.equals("REJECT")) {
            finishTransfer(session);
            System.out.println("[FILE] Rejected offer #" + offerNumber + ".");
        } else {
            CompletableFuture.delayedExecutor(TRANSFER_TIMEOUT_MS, TimeUnit.MILLISECONDS).execute(() -> {
                synchronized (FileTransferController.this) {
                    if (session.state() == FileTransferSession.State.ACCEPTED) {
                        session.fail();
                        events.onFileState(transferId, ClientEvents.FileState.FAILED, "Accepted offer expired");
                        finishTransfer(session);
                        System.err.println("[FILE] Accepted offer expired: #" + offerNumber);
                    }
                }
            });
            System.out.println("[FILE] Accepted offer #" + offerNumber + ". Waiting on peer port " + peerPort);
        }
    }

    public void receiveFileConnection(PacketChannel channel, Packet hello) {
        Socket socket = channel.socket();
        FileTransferSession session = null;
        boolean claimed = false;
        try {
            socket.setSoTimeout(TRANSFER_TIMEOUT_MS);
            FileTransferMeta meta = FileTransferMeta.fromJson(hello.jsonBody());
            session = transferManager.getSession(meta.transferId()).orElseThrow(
                    () -> new IOException("Unknown file transfer"));
            claimIncomingTransfer(session, meta, socket);
            claimed = true;
            File saved = new FileReceiver(channel,
                    downloadDir, session.meta(), session, null).receive();
            events.onFileState(session.transferId(), ClientEvents.FileState.COMPLETED, saved.getAbsolutePath());
            System.out.println("\n[P2P RECEIVED] Saved to: " + saved.getAbsolutePath());
        } catch (IOException | RuntimeException e) {
            if (claimed) {
                session.fail();
                events.onFileState(session.transferId(), ClientEvents.FileState.FAILED, e.getMessage());
            } else {
                try {
                    channel.send(Packet.failure(channel.lastReceivedSequence(), e.getMessage()));
                } catch (IOException ignored) {
                }
            }
            System.err.println("\n[P2P RECEIVE ERROR] " + e.getMessage());
        } finally {
            if (claimed) {
                finishTransfer(session);
            }
        }
    }

    private synchronized void claimIncomingTransfer(FileTransferSession session, FileTransferMeta meta,
                                                     Socket socket) throws IOException {
        PeerConnection peer = transferPeers.get(session.transferId());
        if (peer == null || peer.isClosed() || peers.get(meta.sender()) != peer
                || !meta.receiver().equals(username)
                || !socket.getInetAddress().equals(peer.peerAddress().getAddress())
                || !meta.toJson().equals(session.meta().toJson())
                || session.state() != FileTransferSession.State.ACCEPTED) {
            throw new IOException("Connection does not match an accepted file session");
        }
        trackTransfer(session, socket);
        // Claim atomically, so replayed connections cannot receive into this session twice.
        session.startTransfer();
    }

    private synchronized void recordOfferSequence(String transferId, long sequence) {
        if (transferManager.getSession(transferId).isEmpty()) {
            throw new IllegalStateException("File offer is no longer active");
        }
        offerSequences.put(transferId, sequence);
    }

    private synchronized void registerTransfer(FileTransferSession session, PeerConnection peer, File file)
            throws IOException {
        if (closingTransfers || peer.isClosed() || !peers.containsValue(peer)) {
            throw new IOException("Peer chat connection is closed");
        }
        transferManager.registerSession(session);
        transferPeers.put(session.transferId(), peer);
        if (file != null) {
            transferManager.registerOutboundFile(session.transferId(), file);
        }
    }

    private synchronized void trackTransfer(FileTransferSession session, Socket socket) throws IOException {
        if (closingTransfers || session.isTerminal()
                || transferManager.getSession(session.transferId()).orElse(null) != session
                || transferConnections.putIfAbsent(session.transferId(), socket) != null) {
            throw new IOException("File session is closed or already has a connection");
        }
    }

    private synchronized void finishTransfer(FileTransferSession session) {
        if (transferManager.removeSession(session)) {
            incomingOfferIds.values().removeIf(session.transferId()::equals);
            transferConnections.remove(session.transferId());
            transferPeers.remove(session.transferId());
            offerSequences.remove(session.transferId());
        }
    }

    public synchronized void closeForPeer(PeerConnection peer) {
        for (FileTransferSession session : transferManager.getActiveSessions()) {
            if (peer == null || transferPeers.get(session.transferId()) == peer) {
                session.cancel();
                events.onFileState(session.transferId(), ClientEvents.FileState.FAILED, "Peer connection closed");
                Closeable connection = transferConnections.get(session.transferId());
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (IOException ignored) {
                    }
                }
                finishTransfer(session);
            }
        }
    }

    @Override
    public synchronized void close() {
        closingTransfers = true;
        closeForPeer(null);
    }
}
