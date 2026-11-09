package client;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import protocols.*;

public class PeerConnection implements AutoCloseable {
    private final Socket socket;
    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private int messageRequestId = 1;
    private InetSocketAddress peerAddress;

    public PeerConnection(Socket socket) {
        this.socket = socket;
    }

    public void initiateHandshake(String username, int requestId, int listeningPort) throws IOException {
        socket.setSoTimeout(10000);
        InputStream input = socket.getInputStream();
        Packet hello = new Packet(OpCode.PEER_HELLO, requestId,
                (username + "\n" + listeningPort).getBytes(StandardCharsets.UTF_8));
        sendPacket(hello);
        Packet hello_ack = PacketCodec.read(input);
        if (hello_ack == null
            || hello_ack.op() != OpCode.PEER_HELLO_ACK
            || hello_ack.requestId() != requestId
            || hello_ack.payloadLength() != 0) {
            throw new IOException("Invalid or missing PEER_HELLO_ACK");
        }
        peerAddress = new InetSocketAddress(socket.getInetAddress(), socket.getPort());
        socket.setSoTimeout(0);
    }

    public String acceptHandshake(Packet hello) throws IOException {
        if (hello == null
            || hello.op() != OpCode.PEER_HELLO
            || hello.requestId() <= 0
            || hello.payloadLength() == 0
            || hello.payloadLength() > 4096) {
            throw new IOException("Invalid or missing PEER_HELLO");
        }

        String[] identity = new String(hello.data(), StandardCharsets.UTF_8).split("\n", -1);
        if (identity.length != 2 || identity[0].isBlank()) {
            throw new IOException("Invalid peer identity");
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
        return identity[0];
    }

    public synchronized void sendMessage(String message) throws IOException {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_CHAT_BYTES) {
            throw new IOException("Message exceeds limit: " + MAX_CHAT_BYTES);
        }

        Packet chat_message = new Packet(OpCode.CHAT_MESSAGE, ++messageRequestId, data);
        sendPacket(chat_message);
    }

    public synchronized void sendPacket(Packet packet) throws IOException {
        PacketCodec.write(socket.getOutputStream(), packet);
    }

    public InetSocketAddress peerAddress() {
        return peerAddress;
    }

    public boolean isClosed() {
        return socket.isClosed();
    }

    public void readMessages(String peerUsername, Consumer<Packet> onFilePacket) throws IOException {
        InputStream input = socket.getInputStream();
        Packet packet;

        while ((packet = PacketCodec.read(input)) != null) {
            switch (packet.op()) {
                case CHAT_MESSAGE -> {
                    if (packet.payloadLength() > MAX_CHAT_BYTES) {
                        throw new IOException("Message exceeds chat limit");
                    }
                    System.out.println(peerUsername + ": " + new String(packet.data(), StandardCharsets.UTF_8));
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
