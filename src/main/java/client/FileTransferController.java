package client;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import protocols.FileTransferMeta;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import transfer.FileReceiver;
import transfer.FileSender;
import transfer.FileTransferManager;
import transfer.FileTransferSession;

public final class FileTransferController implements AutoCloseable {
    private static final int TRANSFER_TIMEOUT_MS = 30000;

    private final String username;
    private final int peerPort;
    private final File downloadDir;
    private final Map<String, PeerConnection> peers;
    private final FileTransferManager transferManager = new FileTransferManager();
    private final ConcurrentHashMap<String, Closeable> transferConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PeerConnection> transferPeers = new ConcurrentHashMap<>();
    private final Map<String, String> incomingOfferIds = new HashMap<>();
    private long nextOfferNumber = 1;
    private boolean closingTransfers;

    public FileTransferController(String username, int peerPort, File downloadDir,
                                  Map<String, PeerConnection> peers) {
        this.username = Objects.requireNonNull(username);
        this.peerPort = peerPort;
        this.downloadDir = Objects.requireNonNull(downloadDir);
        this.peers = Objects.requireNonNull(peers);
    }

    public synchronized void handleIncomingOffer(String peerUsername, PeerConnection peer, Packet packet) throws IOException {
        FileTransferMeta meta = FileTransferMeta.fromBytes(packet.data());
        if (!meta.receiver().equals(username) || !meta.sender().equals(peerUsername)
                || meta.sender().equals(username)) {
            throw new IOException("File offer has invalid participants");
        }
        FileTransferSession session = new FileTransferSession(meta);
        registerTransfer(session, peer, null);
        String offerNumber = Long.toString(nextOfferNumber++);
        incomingOfferIds.put(offerNumber, meta.transferId());
        System.out.println("\n[FILE OFFER #" + offerNumber + "] " + meta.sender() + " wants to send '" + meta.fileName()
                + "' (" + meta.fileSize() + " bytes).");
        System.out.println("SHA-256: " + meta.checksum());
        System.out.println("Type '/accept " + offerNumber + "' or '/reject " + offerNumber + "'");
    }

    public synchronized void handleIncomingDecision(PeerConnection peer, Packet packet) throws IOException {
        String[] parts = packet.text().split("\n", -1);
        if (parts.length != 2 || !(parts[1].equals("ACCEPT") || parts[1].equals("REJECT"))) {
            throw new IOException("Invalid FILE_DECISION payload");
        }
        FileTransferSession session = transferManager.getSession(parts[0]).orElse(null);
        if (session == null || transferPeers.get(parts[0]) != peer
                || !session.meta().sender().equals(username)
                || session.state() != FileTransferSession.State.OFFERED) {
            throw new IOException("Decision does not match an outgoing offer on this chat connection");
        }
        if (parts[1].equals("ACCEPT")) {
            File file = transferManager.getOutboundFile(session.transferId());
            session.accept();
            System.out.println("\n[FILE] Transfer '" + session.meta().fileName()
                    + "' was ACCEPTED by " + session.meta().receiver() + ".");
            Thread.startVirtualThread(() -> runP2PSend(file, session, peer));
        } else {
            session.reject();
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
            PacketCodec.write(socket.getOutputStream(), new Packet(OpCode.FILE_OFFER, 0, session.meta().toBytes()));
            FileSender sender = new FileSender(file, session.transferId(), socket.getOutputStream(), session, null);
            String checksum = sender.send();
            sender.awaitResult(socket.getInputStream());
            System.out.println("\n[P2P SENT] " + file.getName() + " completed! SHA-256: " + checksum);
        } catch (IOException | RuntimeException e) {
            session.fail();
            System.err.println("\n[P2P SEND ERROR] " + e.getMessage());
        } finally {
            finishTransfer(session);
        }
    }

    public void offer(PeerConnection peer, String receiver, File file, int requestId) {
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
                registerTransfer(session, peer, file);
                peer.sendPacket(new Packet(OpCode.FILE_OFFER, requestId, meta.toBytes()));
                System.out.println("[FILE] Offer sent to " + receiver + ": '" + file.getName() + "'.");
            } catch (IOException | RuntimeException e) {
                if (session != null) {
                    session.fail();
                    finishTransfer(session);
                }
                System.err.println("[FILE OFFER ERROR] " + e.getMessage());
            }
        });
    }

    public synchronized void sendDecision(String offerNumber, String decision, int requestId) throws IOException {
        // Keep accepting UUIDs for existing console callers; packets always use the UUID.
        String transferId = incomingOfferIds.getOrDefault(offerNumber, offerNumber);
        FileTransferSession session = transferManager.getSession(transferId).orElse(null);
        if (session == null || session.isTerminal()) {
            System.out.println("Offer not found: " + offerNumber);
            return;
        }
        if (!session.meta().receiver().equals(username) || session.state() != FileTransferSession.State.OFFERED) {
            throw new IllegalStateException("This is not a pending incoming offer");
        }
        PeerConnection peer = transferPeers.get(transferId);
        try {
            if (decision.equals("ACCEPT")) {
                session.accept();
            } else {
                session.reject();
            }
            peer.sendPacket(Packet.text(OpCode.FILE_DECISION, requestId, transferId + "\n" + decision));
        } catch (IOException | RuntimeException e) {
            session.fail();
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
                        finishTransfer(session);
                        System.err.println("[FILE] Accepted offer expired: #" + offerNumber);
                    }
                }
            });
            System.out.println("[FILE] Accepted offer #" + offerNumber + ". Waiting on peer port " + peerPort);
        }
    }

    public void receiveFileConnection(Socket socket, Packet hello) {
        FileTransferSession session = null;
        boolean claimed = false;
        try {
            socket.setSoTimeout(TRANSFER_TIMEOUT_MS);
            FileTransferMeta meta = FileTransferMeta.fromBytes(hello.data());
            session = transferManager.getSession(meta.transferId()).orElseThrow(
                    () -> new IOException("Unknown file transfer"));
            claimIncomingTransfer(session, meta, socket);
            claimed = true;
            File saved = new FileReceiver(socket.getInputStream(), socket.getOutputStream(),
                    downloadDir, session.meta(), session, null).receive();
            System.out.println("\n[P2P RECEIVED] Saved to: " + saved.getAbsolutePath());
        } catch (IOException | RuntimeException e) {
            if (claimed) {
                session.fail();
            } else {
                try {
                    PacketCodec.write(socket.getOutputStream(),
                            Packet.text(OpCode.FILE_RESULT, 0, "FAIL: " + e.getMessage()));
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
                || !Arrays.equals(meta.toBytes(), session.meta().toBytes())
                || session.state() != FileTransferSession.State.ACCEPTED) {
            throw new IOException("Connection does not match an accepted file session");
        }
        trackTransfer(session, socket);
        // Claim atomically, so replayed connections cannot receive into this session twice.
        session.startTransfer();
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
        }
    }

    public synchronized void closeForPeer(PeerConnection peer) {
        for (FileTransferSession session : transferManager.getActiveSessions()) {
            if (peer == null || transferPeers.get(session.transferId()) == peer) {
                session.cancel();
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
