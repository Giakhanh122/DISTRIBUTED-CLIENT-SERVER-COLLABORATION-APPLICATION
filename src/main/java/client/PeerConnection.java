package client;

import java.io.IOException;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import protocols.*;

public class PeerConnection implements AutoCloseable {
    private final Socket socket;
    private final PacketChannel channel;
    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private UUID sessionId = UUID.randomUUID();
    private final AtomicBoolean disconnectReported = new AtomicBoolean();
    private InetSocketAddress peerAddress;

    public PeerConnection(PacketChannel channel) {
        this.channel = channel;
        this.socket = channel.socket();
    }

    public void initiateHandshake(String username, int listeningPort) throws IOException {
        socket.setSoTimeout(10000);
        Packet hello = Packet.json(OpCode.PEER_HELLO,
            Json.object("protocolVersion", Packet.PROTOCOL_VERSION, "username", username, "peerPort", listeningPort, "sessionId", sessionId.toString()));
        long helloSequence = channel.send(hello);
        Packet response = PacketValidator.validateResponse(channel.read(), OpCode.PEER_HELLO_ACK, helloSequence);
        PacketValidator.requireSuccess(response);
        if (response.integer("protocolVersion") != Packet.PROTOCOL_VERSION) {
            throw new IOException("Unsupported peer protocol version");
        }
        peerAddress = new InetSocketAddress(socket.getInetAddress(), socket.getPort());
        socket.setSoTimeout(0);
    }

    public String acceptHandshake(Packet hello) throws IOException {
        PacketValidator.validateRequest(hello, OpCode.PEER_HELLO, 1, 4096);

        if (hello.integer("protocolVersion") != Packet.PROTOCOL_VERSION) {
            channel.send(Packet.status(OpCode.PEER_HELLO_ACK, hello.sequence(), "UNSUPPORTED_PROTOCOL_VERSION"));
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
        peerAddress = new InetSocketAddress(socket.getInetAddress(), port);

        Packet response = Packet.reply(OpCode.PEER_HELLO_ACK, hello.sequence(), Json.object("status", "OK", "protocolVersion", Packet.PROTOCOL_VERSION));
        sendPacket(response);

        socket.setSoTimeout(0);
        return peerUsername;
    }

    public void sendMessage(String message) throws IOException {
        Packet chatMessage = Packet.json(OpCode.CHAT_MESSAGE, Json.object("content", message));
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
            switch (packet.op()) {
                case CHAT_MESSAGE -> {
                    if (packet.string("content").getBytes(StandardCharsets.UTF_8).length > MAX_CHAT_BYTES) {
                        throw new IOException("Message exceeds chat limit");
                    }
                    onMessage.accept(packet.string("content"));
                    System.out.println(peerUsername + ": " + packet.string("content"));
                }
                case FILE_OFFER, FILE_DECISION -> onFilePacket.accept(packet);
                default -> throw new IOException("Unexpected peer chat packet: " + packet.op());
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
