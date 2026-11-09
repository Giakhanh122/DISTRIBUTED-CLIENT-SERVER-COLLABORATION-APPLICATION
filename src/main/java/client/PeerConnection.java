package client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import protocols.*;

public class PeerConnection implements AutoCloseable {
    private final Socket socket;
    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private int messageRequestId = 1;

    public PeerConnection(Socket socket) {
        this.socket = socket;
    }

    public void initiateHandshake(String username, int requestId) throws IOException {
        socket.setSoTimeout(10000);
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();

        Packet hello = new Packet(OpCode.PEER_HELLO, requestId, username.getBytes(StandardCharsets.UTF_8));
        PacketCodec.write(output, hello);
        Packet hello_ack = PacketCodec.read(input);
        if (hello_ack == null
            || hello_ack.op() != OpCode.PEER_HELLO_ACK
            || hello_ack.requestId() != requestId
            || hello_ack.payloadLength() != 0) {
            throw new IOException("Invalid or missing PEER_HELLO_ACK");
        }
        socket.setSoTimeout(0);
    }

    public String acceptHandshake() throws IOException {
        socket.setSoTimeout(10000);
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        Packet hello = PacketCodec.read(input);
        if (hello == null
            || hello.op() != OpCode.PEER_HELLO
            || hello.requestId() <= 0
            || hello.payloadLength() == 0
            || hello.payloadLength() > 4096) {
            throw new IOException("Invalid or missing PEER_HELLO");
        }

        String peerUsername = new String(
            hello.data(),
            StandardCharsets.UTF_8
        );
        if (peerUsername.isBlank()) {
            throw new IOException("Peer username must not be blank");
        }

        Packet response = new Packet(
            OpCode.PEER_HELLO_ACK,
            hello.requestId(),
            new byte[0]
        );
        PacketCodec.write(output, response);

        socket.setSoTimeout(0);
        return peerUsername;
    }

    public synchronized void sendMessage(String message) throws IOException {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_CHAT_BYTES) {
            throw new IOException("Message exceeds limit: " + MAX_CHAT_BYTES);
        }

        Packet chat_message = new Packet(OpCode.CHAT_MESSAGE, ++messageRequestId, data);
        PacketCodec.write(socket.getOutputStream(), chat_message);
    }

    public void readMessages(String peerUsername) throws IOException {
        InputStream input = socket.getInputStream();
        Packet packet;

        while ((packet = PacketCodec.read(input)) != null) {
            if (packet.op() != OpCode.CHAT_MESSAGE
                || packet.payloadLength() > MAX_CHAT_BYTES) {
                throw new IOException("Invalid peer chat packet");
            }

            String message = new String(
                packet.data(),
                StandardCharsets.UTF_8
            );

            System.out.println(peerUsername + ": " + message);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
