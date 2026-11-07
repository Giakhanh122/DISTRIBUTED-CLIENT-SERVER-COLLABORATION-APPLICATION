package server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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

    // private String ipAddress;
    // private int portNumber;

    public void start(int portNumber) {
        try (ServerSocket listener = new ServerSocket(portNumber)) {
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

    // DHCP

    public void listenBroadcast() {
        int BROADCAST_PORT = 41234;
        byte[] buffer = new byte[4096];

        try (DatagramSocket socket = new DatagramSocket(BROADCAST_PORT)) {

            socket.setBroadcast(true);

            System.out.println("Listening for discovery on port " + BROADCAST_PORT);

            DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);

            while (true) {
                try {
                    // Reset buffer capacity before each receive
                    udpPacket.setLength(buffer.length);

                    // Receive UDP packet
                    socket.receive(udpPacket);

                    // Extract actual received bytes
                    byte[] receivedData = Arrays.copyOfRange(
                            udpPacket.getData(),
                            udpPacket.getOffset(),
                            udpPacket.getOffset() + udpPacket.getLength());

                    // Deserialize
                    Packet packet = PacketCodec.fromBytes(receivedData);

                    // Validate opcode
                    if (packet.op() != OpCode.DISCOVER_REQUEST) {
                        continue;
                    }

                    String hostAddress = udpPacket.getAddress().getHostAddress();

                    int hostPort = udpPacket.getPort();

                    String message = new String(packet.data(), StandardCharsets.UTF_8);

                    System.out.printf("From client %s:%d: %s%n", hostAddress, hostPort, message);

                    // Create response
                    byte[] responseData = Integer.toString(this.PORT).getBytes(StandardCharsets.UTF_8);

                    Packet responsePacket = new Packet(OpCode.DISCOVER_RESPONSE, packet.requestId(), responseData);

                    // Send response to client
                    PacketCodec.sendUDP(socket, udpPacket.getAddress(), hostPort, responsePacket);

                    System.out.printf("Reply to client %s:%d: TCP port %d%n", hostAddress, hostPort, PORT);

                } catch (Exception e) {
                    System.err.println("Error processing UDP packet: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            System.err.println("Discovery server error: " + e.getMessage());
        }
    }
}
