package server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.xml.crypto.Data;

import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import server.auth.AuthService;
import server.connection.ClientHandler;
import server.session.SessionRegistry;

public class Server {
    private final SessionRegistry sessions = new SessionRegistry();
    private final AuthService authService = new AuthService();

    private static final int PORT = 8080;
    private static final int DISCOVERY_PORT = 41234;

    public void start(int portNumber) {
        try (
            ServerSocket listener = new ServerSocket(portNumber);
            DatagramSocket discoverySocket = new DatagramSocket(DISCOVERY_PORT);
        ) {
            Thread.startVirtualThread(() -> listenDiscovery(discoverySocket, listener.getLocalPort()));

            System.out.println("Server started on port " + portNumber);
            while (true) {
                Socket socket = listener.accept();
                ClientHandler handler = new ClientHandler(socket, sessions, authService);
                Thread.startVirtualThread(handler);
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void listenDiscovery(DatagramSocket socket, int tcpPort) {
        byte[] buffer = new byte[4096];
        byte[] portData = Integer.toString(tcpPort).getBytes(StandardCharsets.UTF_8);

        while (!socket.isClosed()) {
            DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);

            try {
                socket.receive(incoming);
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    System.err.println("Discovery receive error: " + e.getMessage());
                }
                return;
            }

            Packet request;
            try {
                ByteArrayInputStream input = new ByteArrayInputStream(
                    incoming.getData(),
                    incoming.getOffset(),
                    incoming.getLength()
                );

                request = PacketCodec.read(input);
                if (request == null
                    || input.available() != 0
                    || request.op() != OpCode.DISCOVER_REQUEST
                    || request.requestId() <= 0) {
                    continue;
                }
            } catch (IOException e) {
                continue;
            }

            try {
                Packet response = new Packet(OpCode.DISCOVER_RESPONSE, request.requestId(), portData);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                PacketCodec.write(output, response);
                byte[] bytes = output.toByteArray();
                DatagramPacket outgoing = new DatagramPacket(bytes, bytes.length, incoming.getAddress(), incoming.getPort());

                socket.send(outgoing);
            } catch (IOException e) {
                if (socket.isClosed()) return;
                System.err.println("Discovery respone error: " + e.getMessage());
            }
        }
    }
}
