package client.peer;

import client.security.ServerKeyPinning;
import network.PacketChannel;

import java.io.IOException;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import protocols.*;
import security.PeerKeyProof;
import security.SessionKeys;
import security.X25519;

public class PeerConnection implements AutoCloseable {
    @FunctionalInterface
    public interface KeyProofProvider {
        PeerKeyProof request(UUID sessionId, PublicKey publicKey) throws IOException;
    }

    private final Socket socket;
    private final PacketChannel channel;
    private final KeyPair keyPair;
    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private UUID sessionId = UUID.randomUUID();
    private final AtomicBoolean disconnectReported = new AtomicBoolean();
    private InetSocketAddress peerAddress;
    private PublicKey peerPublicKey;

    public PeerConnection(PacketChannel channel) throws IOException {
        this.channel = channel;
        this.socket = channel.socket();
        this.keyPair = createKeyPair();
    }

    public PublicKey publicKey() {
        return keyPair.getPublic();
    }

    private static KeyPair createKeyPair() throws IOException {
        try {
            return X25519.createKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot create peer X25519 key pair", e);
        }
    }

    public void initiateHandshake(int listeningPort, PeerKeyProof proof, String expectedUsername,
                                  ServerKeyPinning serverKeyPinning) throws IOException {
        String encodedPublicKey = Base64.getEncoder().encodeToString(publicKey().getEncoded());
        if (!sessionId.toString().equals(proof.sessionId()) || !encodedPublicKey.equals(proof.publicKey())) {
            throw new IOException("Peer public key confirmation does not match local connection");
        }
        socket.setSoTimeout(10000);
        Packet hello = Packet.json(ConnectionType.PEER_HELLO,
            Json.object("protocolVersion", Packet.PROTOCOL_VERSION,
                    "username", proof.username(), "peerPort", listeningPort, "sessionId", proof.sessionId(),
                    "publicKey", proof.publicKey(), "serverSignature", proof.serverSignature()));
        long helloSequence = channel.send(hello);
        Packet response = PacketValidator.validateResponse(channel.read(), ConnectionType.PEER_HELLO_ACK, helloSequence);
        PacketValidator.requireSuccess(response);
        if (response.integer("protocolVersion") != Packet.PROTOCOL_VERSION) {
            throw new IOException("Unsupported peer protocol version");
        }
        PeerKeyProof peerProof = new PeerKeyProof(response.string("username"), response.string("sessionId"),
                response.string("publicKey"), response.string("serverSignature"));
        try {
            peerPublicKey = serverKeyPinning.verifyPeerKey(peerProof, expectedUsername, sessionId);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot verify responding peer public key", e);
        }
        SessionKeys sessionKeys = deriveSessionKeys(hello, response);
        channel.confirmEncryption(sessionKeys, PacketChannel.Role.INITIATOR);
        peerAddress = new InetSocketAddress(socket.getInetAddress(), socket.getPort());
        socket.setSoTimeout(0);
    }

    public String acceptHandshake(Packet hello, ServerKeyPinning serverKeyPinning,
                                  KeyProofProvider keyProofProvider) throws IOException {
        PacketValidator.validateRequest(hello, ConnectionType.PEER_HELLO, 1, 4096);

        if (hello.integer("protocolVersion") != Packet.PROTOCOL_VERSION) {
            channel.send(Packet.status(ConnectionType.PEER_HELLO_ACK, hello.sequence(), "UNSUPPORTED_PROTOCOL_VERSION"));
            throw new IOException("Unsupported peer protocol version");
        }
        String peerUsername = hello.string("username");
        String id = hello.string("sessionId");
        if (peerUsername.isBlank()) {
            throw new IOException("Invalid peer identity");
        }
        try {
            sessionId = UUID.fromString(id);
            if (!sessionId.toString().equals(id)) {
                throw new IllegalArgumentException("Non-canonical session ID");
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid chat session ID", e);
        }
        int port = hello.integer("peerPort");
        if (port < 1 || port > 65535) {
            throw new IOException("Peer listening port is out of range");
        }
        PeerKeyProof proof = new PeerKeyProof(peerUsername, id,
                hello.string("publicKey"), hello.string("serverSignature"));
        try {
            peerPublicKey = serverKeyPinning.verifyPeerKey(proof, peerUsername, sessionId);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot verify incoming peer public key", e);
        }
        peerAddress = new InetSocketAddress(socket.getInetAddress(), port);

        PeerKeyProof localProof = keyProofProvider.request(sessionId, publicKey());
        Packet response = Packet.reply(ConnectionType.PEER_HELLO_ACK, hello.sequence(),
                Json.object("status", "OK", "protocolVersion", Packet.PROTOCOL_VERSION,
                        "username", localProof.username(), "sessionId", localProof.sessionId(),
                        "publicKey", localProof.publicKey(), "serverSignature", localProof.serverSignature()));
        SessionKeys sessionKeys = deriveSessionKeys(hello, response);
        sendPacket(response);
        channel.confirmEncryption(sessionKeys, PacketChannel.Role.RESPONDER);

        socket.setSoTimeout(0);
        return peerUsername;
    }

    private SessionKeys deriveSessionKeys(Packet hello, Packet response) throws IOException {
        try {
            byte[] handshakeData = Json.bytes(Json.object("purpose", "peer-handshake",
                    "hello", hello.jsonBody(), "ack", response.jsonBody()));
            return X25519.deriveSessionKeys(keyPair, peerPublicKey, handshakeData);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot derive peer session keys", e);
        }
    }

    public void sendMessage(String message) throws IOException {
        Packet chatMessage = Packet.json(ChatType.MESSAGE, Json.object("content", message));
        if (message.getBytes(StandardCharsets.UTF_8).length > MAX_CHAT_BYTES) {
            throw new IOException("Message exceeds limit: " + MAX_CHAT_BYTES);
        }
        sendPacket(chatMessage);
    }

    public long sendPacket(Packet packet) throws IOException {
        return channel.send(packet);
    }

    public long sendPacket(Packet packet, LongConsumer beforeWrite) throws IOException {
        return channel.send(packet, beforeWrite);
    }

    public InetSocketAddress peerAddress() {
        return peerAddress;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public boolean markDisconnectReported() {
        return disconnectReported.compareAndSet(false, true);
    }

    public boolean isClosed() {
        return socket.isClosed();
    }

    public void readMessages(String peerUsername, Consumer<Packet> onFilePacket) throws IOException {
        readMessages(peerUsername, content -> {}, onFilePacket);
    }

    public void readMessages(String peerUsername, Consumer<String> onMessage, Consumer<Packet> onFilePacket) throws IOException {
        Packet packet;
        while ((packet = channel.read()) != null) {
            switch (packet.type()) {
                case ChatType.MESSAGE -> {
                    if (packet.string("content").getBytes(StandardCharsets.UTF_8).length > MAX_CHAT_BYTES) {
                        throw new IOException("Message exceeds chat limit");
                    }
                    onMessage.accept(packet.string("content"));
                }
                case FileType.OFFER, FileType.DECISION -> onFilePacket.accept(packet);
                default -> throw new IOException("Unexpected peer chat packet: " + packet.type());
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
