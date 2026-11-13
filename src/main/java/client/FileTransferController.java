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
import network.PacketChannel;
import transfer.FileReceiver;
import transfer.FileSender;
import transfer.FileTransferRegistry;
import transfer.FileTransferSession;

public final class FileTransferController implements AutoCloseable {
    private static final int TRANSFER_TIMEOUT_MS = 30000;

    private final ClientEvents events;
    private final String username;
    private final int peerPort;
    private final File downloadDir;
    private final Map<String, PeerConnection> peers;
    private final FileTransferRegistry transfers = new FileTransferRegistry();
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
        events.onFileOffer(new FileOffer(meta.transferId(), offerNumber, meta, null, true));
    }

    public synchronized void handleIncomingDecision(PeerConnection peer, Packet packet) throws IOException {
        String transferId = packet.string("transferId");
        String decision = packet.string("decision");
        if (!(decision.equals("ACCEPT") || decision.equals("REJECT"))) {
            throw new IOException("Invalid FILE_DECISION payload");
        }
        FileTransferSession session = transfers.getSession(transferId).orElse(null);
        if (session == null || transferPeers.get(transferId) != peer
                || !session.meta().sender().equals(username)
                || session.state() != FileTransferSession.State.OFFERED
                || !Objects.equals(offerSequences.get(transferId), packet.replyTo())) {
            throw new IOException("Decision does not match an outgoing offer on this chat connection");
        }
        if (decision.equals("ACCEPT")) {
            File file = transfers.getOutboundFile(session.transferId());
            session.accept();
            events.onFileState(transferId, ClientEvents.FileState.TRANSFERRING, "Accepted by " + session.meta().receiver());
            Thread.startVirtualThread(() -> runP2PSend(file, session, peer));
        } else {
            session.reject();
            reportResult(session, ClientEvents.FileState.REJECTED, "Rejected by " + session.meta().receiver());
            finishTransfer(session);
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
            sender.send();
            sender.awaitResult();
            reportResult(session, ClientEvents.FileState.COMPLETED, file.getAbsolutePath());
        } catch (IOException | RuntimeException e) {
            session.fail();
            reportResult(session, ClientEvents.FileState.FAILED, e.getMessage());
            System.err.println("\n[P2P SEND ERROR] " + e.getMessage());
        } finally {
            finishTransfer(session);
        }
    }

    public void offer(PeerConnection peer, String receiver, File file) {
        if (receiver.equals(username)) {
            throw new IllegalArgumentException("Cannot send a file to yourself");
        }
        if (peer == null || peer.isClosed()) throw new IllegalStateException("Peer is not connected: " + receiver);
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
                    events.onFileOffer(new FileOffer(meta.transferId(), null, meta, null, false));
                }
                peer.sendPacket(Packet.json(OpCode.FILE_OFFER, meta.toJson()),
                        sequence -> recordOfferSequence(meta.transferId(), sequence));
            } catch (IOException | RuntimeException e) {
                if (session != null) {
                    session.fail();
                    reportResult(session, ClientEvents.FileState.FAILED, e.getMessage());
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
            session = transfers.getSession(transferId).orElseThrow(
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
            if (decision.equals("ACCEPT")) events.onFileState(transferId, ClientEvents.FileState.TRANSFERRING, decision);
        }
        try {
            peer.sendPacket(response);
        } catch (IOException | RuntimeException e) {
            session.fail();
            reportResult(session, ClientEvents.FileState.FAILED, e.getMessage());
            finishTransfer(session);
            throw e;
        }
        if (decision.equals("REJECT")) {
            reportResult(session, ClientEvents.FileState.REJECTED, "Rejected");
            finishTransfer(session);
        } else {
            CompletableFuture.delayedExecutor(TRANSFER_TIMEOUT_MS, TimeUnit.MILLISECONDS).execute(() -> {
                synchronized (FileTransferController.this) {
                    if (session.state() == FileTransferSession.State.ACCEPTED) {
                        session.fail();
                        reportResult(session, ClientEvents.FileState.FAILED, "Accepted offer expired");
                        finishTransfer(session);
                        System.err.println("[FILE] Accepted offer expired: #" + offerNumber);
                    }
                }
            });
        }
    }

    public void receiveFileConnection(PacketChannel channel, Packet hello) {
        Socket socket = channel.socket();
        FileTransferSession session = null;
        boolean claimed = false;
        try {
            socket.setSoTimeout(TRANSFER_TIMEOUT_MS);
            FileTransferMeta meta = FileTransferMeta.fromJson(hello.jsonBody());
            session = transfers.getSession(meta.transferId()).orElseThrow(
                    () -> new IOException("Unknown file transfer"));
            claimIncomingTransfer(session, meta, socket);
            claimed = true;
            File saved = new FileReceiver(channel,
                    downloadDir, session.meta(), session, null).receive();
            reportResult(session, ClientEvents.FileState.COMPLETED, saved.getAbsolutePath());
        } catch (IOException | RuntimeException e) {
            if (claimed) {
                session.fail();
                reportResult(session, ClientEvents.FileState.FAILED, e.getMessage());
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
        if (transfers.getSession(transferId).isEmpty()) {
            throw new IllegalStateException("File offer is no longer active");
        }
        offerSequences.put(transferId, sequence);
    }

    private synchronized void registerTransfer(FileTransferSession session, PeerConnection peer, File file)
            throws IOException {
        if (closingTransfers || peer.isClosed() || !peers.containsValue(peer)) {
            throw new IOException("Peer chat connection is closed");
        }
        transfers.registerSession(session);
        transferPeers.put(session.transferId(), peer);
        if (file != null) {
            transfers.registerOutboundFile(session.transferId(), file);
        }
    }

    private synchronized void trackTransfer(FileTransferSession session, Socket socket) throws IOException {
        if (closingTransfers || session.isTerminal()
                || transfers.getSession(session.transferId()).orElse(null) != session
                || transferConnections.putIfAbsent(session.transferId(), socket) != null) {
            throw new IOException("File session is closed or already has a connection");
        }
    }

    private void reportResult(FileTransferSession session, ClientEvents.FileState state, String detail) {
        if (session.markResultReported()) events.onFileState(session.transferId(), state, detail);
    }

    private synchronized void finishTransfer(FileTransferSession session) {
        if (transfers.removeSession(session)) {
            incomingOfferIds.values().removeIf(session.transferId()::equals);
            transferConnections.remove(session.transferId());
            transferPeers.remove(session.transferId());
            offerSequences.remove(session.transferId());
        }
    }

    public synchronized void closeForPeer(PeerConnection peer) {
        for (FileTransferSession session : transfers.getActiveSessions()) {
            if (peer == null || transferPeers.get(session.transferId()) == peer) {
                if (session.state() == FileTransferSession.State.COMPLETED) continue;
                session.cancel();
                reportResult(session, ClientEvents.FileState.FAILED, "Peer connection closed");
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
