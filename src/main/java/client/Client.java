package client;

import java.io.IOException;
import java.net.Socket;
import java.util.Scanner;
import protocols.Json;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketChannel;
import protocols.PacketValidator;

/** Public entry point: discovery, handshake, authentication, and one session's lifetime. */
public class Client {
    public record ServerInfo(String address, int port) {}

    public void start(String serverHost, int serverPort, Scanner scanner) {
        try {
            if (serverHost == null || serverHost.isBlank()) {
                ServerInfo info = discover();
                if (info == null) {
                    System.out.println("No server found");
                    return;
                }
                serverHost = info.address();
                serverPort = info.port();
                System.out.println("Server found: " + serverHost + ":" + serverPort);
            }
            connectToServer(serverHost, serverPort, scanner);
        } catch (IOException e) {
            System.err.println("Dicovery failed: " + e.getMessage());
        }
    }

    public void connectToServer(String host, int port, Scanner scanner) {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(10000);
            PacketChannel channel = new PacketChannel(socket);
            if (!handshake(channel)) return;
            ClientConsole.Credentials credentials = ClientConsole.readCredentials(scanner);
            if (credentials == null) return;
            try (ClientSession session = new ClientSession(credentials.username(), host, port, channel)) {
                if (!authenticate(channel, credentials, session.peerPort())) return;
                socket.setSoTimeout(0);
                session.start();
                new ClientConsole(session).run(scanner);
            }
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    public ClientSession login(String host, int port, String username, String password, ClientEvents events)
            throws IOException {
        Socket socket = new Socket();
        ClientSession session = null;
        try {
            socket.connect(new java.net.InetSocketAddress(host, port), 10000);
            socket.setSoTimeout(10000);
            PacketChannel channel = new PacketChannel(socket);
            if (!handshake(channel)) throw new IOException("Server handshake failed");
            session = new ClientSession(username, host, port, channel, events);
            if (!authenticate(channel, new ClientConsole.Credentials(username, password), session.peerPort())) {
                throw new IOException("Login failed");
            }
            socket.setSoTimeout(0);
            return session;
        } catch (IOException | RuntimeException e) {
            if (session != null) session.close();
            try { socket.close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
            throw e;
        }
    }

    public ServerInfo discover() throws IOException {
        return new ServerDiscovery().discover();
    }

    private boolean handshake(PacketChannel channel) throws IOException {
        long sequence = channel.send(Packet.json(OpCode.HELLO,
                Json.object("protocolVersion", Packet.PROTOCOL_VERSION)));
        Packet response = channel.read();
        try {
            PacketValidator.validateResponse(response, OpCode.HELLO_ACK, sequence);
            PacketValidator.requireSuccess(response);
            if (response.integer("protocolVersion") != Packet.PROTOCOL_VERSION) {
                throw new IOException("Unsupported protocol version");
            }
        } catch (IOException e) {
            System.err.println("Invalid or missing HELLO_ACK");
            return false;
        }
        System.out.println("Handshake successful!");
        return true;
    }

    private boolean authenticate(PacketChannel channel, ClientConsole.Credentials credentials, int peerPort)
            throws IOException {
        long sequence = channel.send(Packet.json(OpCode.AUTH_REQUEST,
                Json.object("username", credentials.username(), "password", credentials.password(), "peerPort", peerPort)));
        Packet response = channel.read();
        try {
            PacketValidator.validateResponse(response, OpCode.AUTH_RESPONSE, sequence);
        } catch (IOException e) {
            System.err.println("Invalid or missing AUTH_RESPONSE");
            return false;
        }
        if (!response.status().equals("OK")) {
            System.err.println("Login failed: " + response.status());
            return false;
        }
        System.out.println("Login successful!");
        return true;
    }
}
