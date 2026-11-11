package client;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import protocols.*;

public class PeerConnection implements AutoCloseable {
    private final Socket socket;
    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private UUID sessionId = UUID.randomUUID();
    private final AtomicBoolean disconnectReported = new AtomicBoolean();
    private int messageRequestId = 1;
    private InetSocketAddress peerAddress;
    private volatile String peerUsername;

    public PeerConnection(Socket socket) {
        this.socket = socket;
    }

    public void initiateHandshake(String username, int requestId, int listeningPort) throws IOException {
        socket.setSoTimeout(10000);
        InputStream input = socket.getInputStream();
        Packet hello = Packet.text(OpCode.PEER_HELLO, requestId,
            username + "\n" + listeningPort + "\n" + sessionId);
        sendPacket(hello);
        Packet response = PacketValidator.validateResponse(PacketCodec.read(input), OpCode.PEER_HELLO_ACK, requestId);
        if (response.payloadLength() != 0) {
            throw new IOException("PEER_HELLO_ACK payload must be empty");
        }
        peerAddress = new InetSocketAddress(socket.getInetAddress(), socket.getPort());
        socket.setSoTimeout(0);
    }

    public String acceptHandshake(Packet hello) throws IOException {
        PacketValidator.validateRequest(hello, OpCode.PEER_HELLO, 1, 4096);

        String[] identity = hello.text().split("\n", -1);
        if (identity.length != 3 || identity[0].isBlank()) {
            throw new IOException("Invalid peer identity");
        }
        try {
            sessionId = UUID.fromString(identity[2]);
            if (!sessionId.toString().equals(identity[2])) {
                throw new IllegalArgumentException("Non-canonical session ID");
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid chat session ID", e);
        }
        int port;
        try {
            port = Integer.parseInt(identity[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid peer listening port", e);
        }
        if (port < 1 || port > 65535) {
            throw new IOException("Peer listening port is out of range");
        }
        peerAddress = new InetSocketAddress(socket.getInetAddress(), port);

        Packet response = new Packet(
            OpCode.PEER_HELLO_ACK,
            hello.requestId(),
            new byte[0]
        );
        sendPacket(response);

        socket.setSoTimeout(0);
        this.peerUsername = identity[0];
        return identity[0];
    }

    public synchronized void sendMessage(String message) throws IOException {
        Packet chatMessage = Packet.text(OpCode.CHAT_MESSAGE, messageRequestId + 1, message);
        if (chatMessage.payloadLength() > MAX_CHAT_BYTES) {
            throw new IOException("Message exceeds limit: " + MAX_CHAT_BYTES);
        }
        messageRequestId++;
        sendPacket(chatMessage);
    }

    public synchronized void sendPacket(Packet packet) throws IOException {
        PacketCodec.write(socket.getOutputStream(), packet);
    }

    public InetSocketAddress peerAddress() {
        return peerAddress;
    }

    public String getPeerUsername() {
        return peerUsername;
    }

    public void setPeerUsername(String peerUsername) {
        this.peerUsername = peerUsername;
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

    public boolean isConnected() {
        return socket.isConnected() && !socket.isClosed();
    }

    public void readMessages(String peerUsername, Consumer<Packet> onFilePacket) throws IOException {
        readMessages(peerUsername, text -> System.out.println(peerUsername + ": " + text), onFilePacket);
    }

    public void readMessages(String peerUsername, Consumer<String> onChatMessage, Consumer<Packet> onFilePacket) throws IOException {
        InputStream input = socket.getInputStream();
        Packet packet;

        while ((packet = PacketCodec.read(input)) != null) {
            switch (packet.op()) {
                case CHAT_MESSAGE -> {
                    if (packet.payloadLength() > MAX_CHAT_BYTES) {
                        throw new IOException("Message exceeds chat limit");
                    }
                    if (onChatMessage != null) {
                        onChatMessage.accept(packet.text());
                    } else {
                        System.out.println(peerUsername + ": " + packet.text());
                    }
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
