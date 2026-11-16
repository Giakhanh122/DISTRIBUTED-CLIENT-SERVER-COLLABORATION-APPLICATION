package client.transfer;

import protocols.type.FileType;

import client.ClientEvents;
import client.peer.PeerConnection;
import com.google.gson.JsonObject;
import network.PacketChannel;
import protocols.FileTransferMeta;
import protocols.Json;
import protocols.Packet;
import protocols.PacketValidator;
import security.SessionKeys;
import security.X25519;
import transfer.FileReceiver;
import transfer.FileSender;
import transfer.FileTransferSession;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class FileTransferController implements AutoCloseable {
    private static final int TRANSFER_TIMEOUT_MS = 30000;
    private static final int OFFER_TIMEOUT_MS = (int) TimeUnit.MINUTES.toMillis(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Mutable fields belong to this controller's lock; the session has its own state lock. */
    private static final class Transfer {
        final FileTransferSession session;
        final PeerConnection peer;
        final File source;
        String offerNumber;
        long offerSequence;
        KeyPair keyPair;
        PublicKey peerPublicKey;
        SessionKeys keys;
        Socket socket;
        boolean workerActive;
        volatile boolean cancelRequested;
        long cancelSequence;
        final java.util.concurrent.CompletableFuture<Void> cancelAck = new java.util.concurrent.CompletableFuture<>();
        ScheduledFuture<?> timeout;

        Transfer(FileTransferMeta meta, PeerConnection peer, File source) {
            session = new FileTransferSession(meta);
            this.peer = peer;
            this.source = source;
        }

        String id() { return session.transferId(); }
    }

    private final ClientEvents events;
    private final String username;
    private final File downloadDir;
    private final Map<String, PeerConnection> peers;
    private final Map<String, Transfer> transfers = new HashMap<>();
    private final ScheduledThreadPoolExecutor timeoutExecutor = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("file-transfer-timeouts").factory());
    private final LongSupplier nextOfferNumber;
    private boolean closed;

    public FileTransferController(String username, File downloadDir, Map<String, PeerConnection> peers,
                                  LongSupplier nextOfferNumber, ClientEvents events) {
        this.events = Objects.requireNonNull(events);
        this.nextOfferNumber = Objects.requireNonNull(nextOfferNumber);
        this.username = Objects.requireNonNull(username);
        this.downloadDir = Objects.requireNonNull(downloadDir);
        this.peers = Objects.requireNonNull(peers);
        timeoutExecutor.setRemoveOnCancelPolicy(true);
    }

    public synchronized void handleIncomingOffer(String peerUsername, PeerConnection peer, Packet packet)
            throws IOException {
        FileTransferMeta meta = FileTransferMeta.fromJson(packet.jsonBody());
        if (!meta.receiver().equals(username) || !meta.sender().equals(peerUsername)
                || meta.sender().equals(username)) throw new IOException("File offer has invalid participants");
        Transfer transfer = new Transfer(meta, peer, null);
        transfer.peerPublicKey = decodePublicKey(packet.string("publicKey"));
        transfer.offerSequence = packet.sequence();
        transfer.offerNumber = Long.toString(nextOfferNumber.getAsLong());
        register(transfer);
        events.onFileOffer(new FileOffer(transfer.id(), transfer.offerNumber, meta, null, true));
    }

    public synchronized void handleIncomingDecision(PeerConnection peer, Packet packet) throws IOException {
        String decision = packet.string("decision");
        if (!decision.equals("ACCEPT") && !decision.equals("REJECT")) throw new IOException("Invalid file decision");
        Transfer transfer = transfers.get(packet.string("transferId"));
        if (transfer == null || transfer.peer != peer || !transfer.session.meta().sender().equals(username)
                || transfer.session.state() != FileTransferSession.State.OFFERED
                || transfer.offerSequence != packet.replyTo()) {
            throw new IOException("Decision does not match an outgoing offer on this chat connection");
        }
        if (decision.equals("REJECT")) {
            transfer.session.reject();
            try { report(transfer, ClientEvents.FileState.REJECTED, "Rejected by receiver"); }
            finally { finish(transfer); }
            return;
        }
        try {
            transfer.keys = deriveKeys(transfer, transfer.keyPair, decodePublicKey(packet.string("publicKey")));
            transfer.keyPair = null;
            transfer.session.accept();
            cancelTimeout(transfer);
            events.onFileState(transfer.id(), ClientEvents.FileState.TRANSFERRING,
                    "Accepted by " + transfer.session.meta().receiver());
            transfer.workerActive = true;
            Thread.startVirtualThread(() -> sendFile(transfer));
        } catch (IOException | RuntimeException e) {
            fail(transfer, e);
            throw e;
        }
    }

    public void cancel(String id) {
        Transfer transfer;
        synchronized (this) { transfer = findOffer(id); }
        if (transfer != null) cancel(transfer, true);
    }

    public void handleIncomingCancel(PeerConnection peer, Packet packet) throws IOException {
        PacketValidator.validateRequest(packet, FileType.FILE_CANCEL, 1, 1024);
        String id = packet.string("transferId");
        Transfer transfer;
        synchronized (this) {
            transfer = transfers.get(id);
            if (transfer != null && transfer.peer != peer) throw new IOException("Cancellation peer mismatch");
        }
        if (transfer != null) cancel(transfer, false);
        peer.sendPacket(Packet.reply(FileType.FILE_CANCEL_ACK, packet.sequence(),
                Json.object("status", "OK", "transferId", id)));
    }

    public synchronized void handleIncomingCancelAck(PeerConnection peer, Packet packet) throws IOException {
        Transfer transfer = transfers.get(packet.string("transferId"));
        if (transfer == null) return;
        if (transfer.peer != peer || !transfer.cancelRequested
                || transfer.cancelSequence != packet.replyTo()) throw new IOException("Cancellation ACK mismatch");
        PacketValidator.requireSuccess(packet);
        transfer.cancelAck.complete(null);
    }

    private void cancel(Transfer transfer, boolean notifyPeer) {
        if (notifyPeer) {
            synchronized (this) {
                if (transfers.get(transfer.id()) != transfer || transfer.session.isTerminal()
                        || transfer.cancelRequested) return;
                transfer.cancelRequested = true;
                cancelTimeout(transfer);
                events.onFileState(transfer.id(), ClientEvents.FileState.CANCELING, "Canceling file transfer");
            }
            // Give the remote chat reader time to mark CANCELED before closing the
            // independent data socket, whose EOF could otherwise win the race.
            try {
                transfer.peer.sendPacket(Packet.json(FileType.FILE_CANCEL, Json.object("transferId", transfer.id())),
                        sequence -> { synchronized (this) { transfer.cancelSequence = sequence; } });
                transfer.cancelAck.get(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                System.err.println("Cancellation confirmation unavailable: " + message(e));
            } finally {
                cancel(transfer, false);
            }
            return;
        }
        synchronized (this) {
            if (transfers.get(transfer.id()) != transfer) return;
            synchronized (transfer.session) {
                if (transfer.session.isTerminal()) return;
                transfer.session.cancel();
            }
            cancelTimeout(transfer);
            events.onFileState(transfer.id(), ClientEvents.FileState.CANCELING, "Canceling file transfer");
            if (transfer.socket != null) {
                try { transfer.socket.close(); }
                catch (IOException e) { System.err.println("Cannot close canceled transfer: " + message(e)); }
            }
            if (!transfer.workerActive) {
                report(transfer, ClientEvents.FileState.CANCELED, "File transfer canceled");
                finish(transfer);
            }
        }
    }

    public void offer(PeerConnection peer, String receiver, File file) {
        if (receiver.equals(username)) throw new IllegalArgumentException("Cannot send a file to yourself");
        if (peer == null || peer.isClosed()) throw new IllegalStateException("Peer is not connected: " + receiver);
        Thread.startVirtualThread(() -> {
            Transfer transfer = null;
            try {
                long size = file.length();
                String checksum = FileSender.checksum(file);
                if (file.length() != size) throw new IOException("File changed while preparing offer");
                FileTransferMeta meta = FileTransferMeta.create(username, receiver, file.getName(), size, checksum);
                transfer = new Transfer(meta, peer, file);
                transfer.keyPair = createKeyPair();
                JsonObject body = meta.toJson();
                body.addProperty("publicKey", encode(transfer.keyPair.getPublic()));
                synchronized (this) {
                    register(transfer);
                    events.onFileOffer(new FileOffer(transfer.id(), null, meta, null, false, file.getAbsolutePath()));
                }
                Transfer offered = transfer;
                peer.sendPacket(Packet.json(FileType.FILE_OFFER, body), sequence -> recordOfferSequence(offered, sequence));
            } catch (IOException | RuntimeException e) {
                if (transfer != null) fail(transfer, e);
                else events.onFileState("", ClientEvents.FileState.FAILED, message(e));
            }
        });
    }

    public void sendDecision(String offerNumber, String decision) throws IOException {
        if (!decision.equals("ACCEPT") && !decision.equals("REJECT")) {
            throw new IllegalArgumentException("Invalid file decision");
        }
        Transfer transfer;
        Packet response;
        synchronized (this) {
            transfer = findOffer(offerNumber);
            if (transfer == null || !transfer.session.meta().receiver().equals(username)
                    || transfer.session.state() != FileTransferSession.State.OFFERED) {
                throw new IOException("This is not a pending incoming offer");
            }
            JsonObject body = Json.object("transferId", transfer.id(), "decision", decision);
            try {
                if (decision.equals("ACCEPT")) {
                    KeyPair keyPair = createKeyPair();
                    transfer.keys = deriveKeys(transfer, keyPair, transfer.peerPublicKey);
                    transfer.peerPublicKey = null;
                    body.addProperty("publicKey", encode(keyPair.getPublic()));
                    transfer.session.accept();
                    scheduleTimeout(transfer, FileTransferSession.State.ACCEPTED, TRANSFER_TIMEOUT_MS,
                            "File connection or key confirmation timed out");
                    events.onFileState(transfer.id(), ClientEvents.FileState.TRANSFERRING, "Accepted");
                } else {
                    transfer.session.reject();
                    cancelTimeout(transfer);
                }
                response = Packet.reply(FileType.FILE_DECISION, transfer.offerSequence, body);
            } catch (IOException | RuntimeException e) {
                fail(transfer, e);
                throw e;
            }
        }
        try {
            transfer.peer.sendPacket(response);
        } catch (IOException | RuntimeException e) {
            fail(transfer, e);
            throw e;
        }
        if (decision.equals("REJECT")) {
            try { report(transfer, ClientEvents.FileState.REJECTED, "Rejected"); }
            finally { finish(transfer); }
        }
    }

    private void sendFile(Transfer transfer) {
        try (Socket socket = new Socket()) {
            SessionKeys keys;
            synchronized (this) {
                requireActive(transfer, FileTransferSession.State.ACCEPTED);
                transfer.socket = socket;
                keys = transfer.keys;
                transfer.keys = null;
            }
            socket.connect(transfer.peer.peerAddress(), 10000);
            socket.setSoTimeout(TRANSFER_TIMEOUT_MS);
            PacketChannel channel = new PacketChannel(socket);
            long sequence = channel.send(Packet.json(FileType.FILE_TRANSFER_START, Json.object("transferId", transfer.id())));
            Packet ready = PacketValidator.validateResponse(channel.read(), FileType.FILE_READY, sequence);
            PacketValidator.requireSuccess(ready);
            if (!transfer.id().equals(ready.string("transferId"))) throw new IOException("File channel transfer ID mismatch");
            SessionKeys connectionKeys = connectionKeys(keys, ready.string("connectionNonce"));
            channel.confirmEncryption(connectionKeys, PacketChannel.Role.INITIATOR);
            FileSender sender = new FileSender(transfer.source, transfer.id(), channel, transfer.session, new FileProgress(events, transfer.id()));
            sender.send();
            sender.awaitResult();
            report(transfer, ClientEvents.FileState.COMPLETED, transfer.source.getAbsolutePath());
        } catch (IOException | RuntimeException e) {
            fail(transfer, e);
        } finally {
            finish(transfer);
        }
    }

    public void receiveFileConnection(PacketChannel channel, Packet hello) {
        Transfer transfer = null;
        boolean claimed = false;
        try {
            Socket socket = channel.socket();
            socket.setSoTimeout(TRANSFER_TIMEOUT_MS);
            PacketValidator.validateRequest(hello, FileType.FILE_TRANSFER_START, 1, 256);
            SessionKeys baseKeys;
            synchronized (this) {
                transfer = transfers.get(hello.string("transferId"));
                if (transfer == null || !transfer.session.meta().receiver().equals(username)) {
                    throw new IOException("Unknown incoming file transfer");
                }
                requireActive(transfer, FileTransferSession.State.ACCEPTED);
                if (transfer.socket != null) throw new IOException("File transfer already claimed");
                baseKeys = transfer.keys;
            }
            // Each attempt gets fresh traffic keys. Failed authentication leaves the offer untouched.
            byte[] nonce = new byte[32];
            RANDOM.nextBytes(nonce);
            SessionKeys connectionKeys = connectionKeys(baseKeys, Base64.getEncoder().encodeToString(nonce));
            channel.send(Packet.reply(FileType.FILE_READY, hello.sequence(),
                    Json.object("status", "OK", "transferId", transfer.id(),
                            "connectionNonce", Base64.getEncoder().encodeToString(nonce))));
            channel.confirmEncryption(connectionKeys, PacketChannel.Role.RESPONDER);
            synchronized (this) {
                requireActive(transfer, FileTransferSession.State.ACCEPTED);
                if (transfer.socket != null) throw new IOException("File transfer already claimed");
                transfer.socket = socket;
                transfer.keys = null;
                transfer.session.startTransfer();
                cancelTimeout(transfer);
                transfer.workerActive = true;
                claimed = true;
            }
            File saved = new FileReceiver(channel, downloadDir, transfer.session.meta(), transfer.session, new FileProgress(events, transfer.id())).receive();
            report(transfer, ClientEvents.FileState.COMPLETED, saved.getAbsolutePath());
        } catch (IOException | RuntimeException e) {
            if (claimed) fail(transfer, e);
            else {
                // No mutation of a pending transfer for an unauthenticated or duplicate socket.
                try { channel.send(Packet.failure(channel.lastReceivedSequence(), message(e))); }
                catch (IOException ignored) { }
            }
        } finally {
            if (claimed) finish(transfer);
            try { channel.close(); }
            catch (IOException e) { System.err.println("Cannot close file socket: " + message(e)); }
        }
    }

    private static SessionKeys connectionKeys(SessionKeys baseKeys, String encodedNonce) throws IOException {
        if (baseKeys == null) throw new IOException("Missing file transfer keys");
        try {
            return baseKeys.forFileConnection(Base64.getDecoder().decode(encodedNonce));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IOException("Invalid file connection nonce or keys", e);
        }
    }

    private SessionKeys deriveKeys(Transfer transfer, KeyPair keyPair, PublicKey peerPublicKey) throws IOException {
        if (keyPair == null || peerPublicKey == null) throw new IOException("Missing file transfer keys");
        boolean sender = transfer.session.meta().sender().equals(username);
        byte[] transcript = Json.bytes(Json.object("purpose", "peer-file-transfer",
                "protocolVersion", Packet.PROTOCOL_VERSION, "meta", transfer.session.meta().toJson(),
                "senderPublicKey", encode(sender ? keyPair.getPublic() : peerPublicKey),
                "receiverPublicKey", encode(sender ? peerPublicKey : keyPair.getPublic())));
        try {
            return X25519.deriveSessionKeys(keyPair, peerPublicKey, transcript);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot derive file transfer keys", e);
        }
    }

    private static KeyPair createKeyPair() throws IOException {
        try { return X25519.createKeyPair(); }
        catch (GeneralSecurityException e) { throw new IOException("Cannot create file transfer key pair", e); }
    }

    private static PublicKey decodePublicKey(String publicKey) throws IOException {
        try { return X25519.decodePublicKey(publicKey); }
        catch (IllegalArgumentException | GeneralSecurityException e) { throw new IOException("Invalid file public key", e); }
    }

    private static String encode(PublicKey key) { return Base64.getEncoder().encodeToString(key.getEncoded()); }
    private static String message(Exception e) { return Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()); }

    private Transfer findOffer(String number) {
        Transfer transfer = transfers.get(number);
        if (transfer != null) return transfer;
        return transfers.values().stream().filter(value -> number.equals(value.offerNumber)).findFirst().orElse(null);
    }

    private void register(Transfer transfer) throws IOException {
        String participant = transfer.session.meta().sender().equals(username)
                ? transfer.session.meta().receiver() : transfer.session.meta().sender();
        if (closed || transfer.peer.isClosed() || peers.get(participant) != transfer.peer) {
            throw new IOException("Peer chat connection is closed");
        }
        if (transfers.putIfAbsent(transfer.id(), transfer) != null) throw new IOException("Duplicate file transfer ID");
        scheduleTimeout(transfer, FileTransferSession.State.OFFERED, OFFER_TIMEOUT_MS, "File offer expired");
    }

    private void requireActive(Transfer transfer, FileTransferSession.State state) throws IOException {
        String participant = transfer.session.meta().sender().equals(username)
                ? transfer.session.meta().receiver() : transfer.session.meta().sender();
        if (closed || transfers.get(transfer.id()) != transfer || transfer.peer.isClosed()
                || peers.get(participant) != transfer.peer || transfer.session.state() != state) {
            throw new IOException("File transfer is no longer active");
        }
    }

    private synchronized void recordOfferSequence(Transfer transfer, long sequence) {
        if (transfers.get(transfer.id()) != transfer || closed) throw new IllegalStateException("File offer is no longer active");
        transfer.offerSequence = sequence;
    }

    private void scheduleTimeout(Transfer transfer, FileTransferSession.State state, int delayMillis, String reason) {
        cancelTimeout(transfer);
        transfer.timeout = timeoutExecutor.schedule(() -> {
            synchronized (FileTransferController.this) {
                if (transfers.get(transfer.id()) != transfer) return;
                synchronized (transfer.session) {
                    if (transfer.session.state() != state) return;
                    transfer.session.fail();
                }
                ClientEvents.FileState result = state == FileTransferSession.State.OFFERED
                        ? ClientEvents.FileState.EXPIRED : ClientEvents.FileState.FAILED;
                try { report(transfer, result, reason); }
                finally { finish(transfer); }
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelTimeout(Transfer transfer) {
        if (transfer.timeout != null) {
            transfer.timeout.cancel(false);
            transfer.timeout = null;
        }
    }

    private void report(Transfer transfer, ClientEvents.FileState state, String detail) {
        if (transfer.session.markResultReported()) events.onFileState(transfer.id(), state, detail);
    }

    private void fail(Transfer transfer, Exception failure) {
        transfer.session.fail();
        ClientEvents.FileState result = (transfer.cancelRequested || transfer.session.state() == FileTransferSession.State.CANCELED)
                && !(failure instanceof FileReceiver.CleanupException)
                ? ClientEvents.FileState.CANCELED : ClientEvents.FileState.FAILED;
        try { report(transfer, result, result == ClientEvents.FileState.CANCELED ? "File transfer canceled" : message(failure)); }
        finally { finish(transfer); }
    }

    private synchronized void finish(Transfer transfer) {
        if (!transfers.remove(transfer.id(), transfer)) return;
        cancelTimeout(transfer);
        transfer.cancelAck.complete(null);
        transfer.keyPair = null;
        transfer.peerPublicKey = null;
        transfer.keys = null;
        if (transfer.socket != null) {
            try { transfer.socket.close(); }
            catch (IOException e) { System.err.println("Cannot close file transfer: " + message(e)); }
            transfer.socket = null;
        }
    }

    public synchronized void closeForPeer(PeerConnection peer) {
        for (Transfer transfer : List.copyOf(transfers.values())) {
            if (peer != null && transfer.peer != peer) continue;
            try {
                boolean cancelled;
                synchronized (transfer.session) {
                    cancelled = transfer.session.state() != FileTransferSession.State.COMPLETED;
                    if (cancelled) transfer.session.cancel();
                }
                if (cancelled) {
                    report(transfer, ClientEvents.FileState.FAILED, "Peer connection closed");
                }
            } finally { finish(transfer); }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        try { closeForPeer(null); }
        finally { timeoutExecutor.shutdownNow(); }
    }
}
