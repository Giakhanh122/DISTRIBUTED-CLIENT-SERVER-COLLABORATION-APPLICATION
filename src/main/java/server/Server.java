package server;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;

public class Server {
    private static final int MAX_CHAT_BYTES = 64 * 1024;

    public void start(int portNumber) {
        try (ServerSocket listener = new ServerSocket(portNumber)) {
            System.out.println("Server started on port " + portNumber);

            while (true) {
                Socket socket = listener.accept();
                Thread.startVirtualThread(() -> handleClient(socket));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket socket) {
        String address = socket.getRemoteSocketAddress().toString();

        try (socket) {
            socket.setSoTimeout(5000); 
            InputStream input = socket.getInputStream();
            Packet hello = PacketCodec.read(input);

            if (hello == null || hello.op() != OpCode.HELLO || hello.payloadLength() != 0) {
                System.err.println("Invalid or missing HELLO from " + address);
                return;
            }

            PacketCodec.write(
                socket.getOutputStream(),
                new Packet(OpCode.HELLO_ACK, hello.requestId(), new byte[0])
            );
            System.out.println("Handshake successful with " + address);
            socket.setSoTimeout(0);

            Packet packet;
            while ((packet = PacketCodec.read(input)) != null) {
                if (packet.op() != OpCode.CHAT_MESSAGE || packet.payloadLength() > MAX_CHAT_BYTES) {
                    System.err.println("Unexpected packet from " + address + ": " + packet.op());
                    return;
                }
                System.out.println(address + ": " + new String(packet.data(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            System.err.println("Client " + address + " error: " + e.getMessage());
        } finally {
            System.out.println("Client " + address + " disconnected");
        }
    }
}
