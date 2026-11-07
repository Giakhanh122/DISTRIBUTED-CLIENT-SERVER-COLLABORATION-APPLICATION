package server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
            System.out.println("Listening for discovery message from port " + BROADCAST_PORT);
            socket.setBroadcast(true);

            DatagramPacket UDPPacket = new DatagramPacket(buffer, buffer.length);
            while (true) {
                try {
                    socket.receive(UDPPacket);
                    Packet packet = PacketCodec.fromBytes(UDPPacket.getData());

                    String hostAddress = UDPPacket.getAddress().getHostAddress();
                    int hostPort = UDPPacket.getPort();
                    System.out.printf("From client %s:%d: %s%n", hostAddress, hostPort, packet.data());

                    // reply
                    byte[] message = Integer.toString(this.PORT).getBytes(StandardCharsets.UTF_8);
                    Packet ResponsePacket = new Packet(OpCode.DISCOVER_RESPONSE, 1, message);

                    PacketCodec.sendUDP(socket, InetAddress.getByName(hostAddress), hostPort, ResponsePacket);

                    System.out.printf("Reply to client %s:%d: %d%n", hostAddress, hostPort, PORT);

                } catch (Exception e) {
                    System.err.print(e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.print(e.getMessage());
        }
    }
}
