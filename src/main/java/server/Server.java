package server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;

import protocols.OpCode;
import protocols.Json;
import protocols.Packet;
import protocols.PacketCodec;
import protocols.PacketValidator;
import server.auth.AuthService;
import server.broadcast.BroadcastFileRegistry;
import server.community.CommunityRegistry;
import server.community.CommunityService;
import server.connection.ClientHandler;
import server.session.ChatSessionRegistry;
import server.session.UserSessionRegistry;
import server.storage.FileStorageService;

public class Server {
    private final UserSessionRegistry userSessions = new UserSessionRegistry();
    private final ChatSessionRegistry chatSessions = new ChatSessionRegistry();
    private final AuthService authService = new AuthService();
    private final CommunityRegistry communityRegistry = new CommunityRegistry();
    private final CommunityService communityService = new CommunityService(communityRegistry, userSessions);
    private final BroadcastFileRegistry broadcastFileRegistry = new BroadcastFileRegistry();
    private final FileStorageService fileStorageService = new FileStorageService(Path.of("server_storage"));

    private static final int PORT = 8080;
    private static final int DISCOVERY_PORT = 41234;

    public static void main(String[] args) {
        new Server().start(args.length == 0 ? PORT : Integer.parseInt(args[0]));
    }

    public void start(int portNumber) {
        try (
            ServerSocket listener = new ServerSocket(portNumber);
            DatagramSocket discoverySocket = new DatagramSocket(DISCOVERY_PORT);
        ) {
            Thread.startVirtualThread(() -> listenDiscovery(discoverySocket, listener.getLocalPort()));

            printServerAddresses(listener.getLocalPort());
            System.out.println("Server started on port " + listener.getLocalPort());
            while (true) {
                Socket socket = listener.accept();
                ClientHandler handler = new ClientHandler(socket, userSessions, chatSessions,
                        authService, communityService, broadcastFileRegistry, fileStorageService);
                Thread.startVirtualThread(handler);
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void printServerAddresses(int port) {
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                for (var address : network.getInterfaceAddresses()) {
                    if (address.getAddress() instanceof Inet4Address ip) {
                        System.out.println("Server address (" + network.getDisplayName() + "): "
                                + ip.getHostAddress() + ":" + port);
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Cannot list server addresses: " + e.getMessage());
        }
    }

    private void listenDiscovery(DatagramSocket socket, int tcpPort) {
        byte[] buffer = new byte[4096];

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
                PacketValidator.validateRequest(request, OpCode.DISCOVER_REQUEST, 0, 0);
                if (request.sequence() != 1 || input.available() != 0) {
                    continue;
                }
            } catch (IOException e) {
                continue;
            }

            try {
                Packet response = Packet.reply(OpCode.DISCOVER_RESPONSE, request.sequence(), Json.object("status", "OK", "tcpPort", tcpPort));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                PacketCodec.write(output, response, 1);
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
