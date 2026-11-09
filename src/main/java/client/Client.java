package client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import client.PeerListener;

public class Client {
    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private String username;
    private final ConcurrentHashMap<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private volatile String selectedPeerUsername;
    private boolean closingPeers;
    private int requestSequence = 0;

    public record ServerInfo(String address, int port) {}

    private int nextRequestId() {
        return ++requestSequence;
    }

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

    public void connectToServer(String host, int portNumber, Scanner scanner) {
        try (
            Socket socket = new Socket(host, portNumber);
            PeerListener peerListener = new PeerListener(this::registerPeer);
        ) {
            socket.setSoTimeout(10000);

            int peerPort = peerListener.getPort();
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            if (!handshake(input, output, nextRequestId())) {
                return;
            }

            if (!login(input, output, scanner, nextRequestId(), peerPort)) {
                return;
            }

            Thread.startVirtualThread(peerListener);
            socket.setSoTimeout(0);
            readConsole(input, output, scanner);
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        } finally {
            closePeers();
        }
    }

    private boolean handshake(InputStream input, OutputStream output, int requestId)
            throws IOException {
        Packet hello = new Packet(OpCode.HELLO, requestId, new byte[0]);
        PacketCodec.write(output, hello);

        Packet response = PacketCodec.read(input);
        if (response == null
                || response.op() != OpCode.HELLO_ACK
                || response.requestId() != requestId
                || response.payloadLength() != 0) {
            System.err.println("Invalid or missing HELLO_ACK");
            return false;
        }

        System.out.println("Handshake succesful!");
        return true;
    }

    private void readConsole(InputStream input, OutputStream output, Scanner scanner) throws IOException {
        System.out.println(
            "Commands: /users, /lookup <username>, /logout"
        );

        while (scanner.hasNextLine()) {
            String message = scanner.nextLine();
            String command = message.trim();

            if (command.equalsIgnoreCase("/logout"))
                return;
            if (message.isBlank())
                continue;

            if (command.equalsIgnoreCase("/users")) {
                requestUserList(input, output, nextRequestId());
                continue;
            }

            String[] arguments = command.split("\\s+", 2);
            if (arguments[0].equalsIgnoreCase("/lookup")) {
                if (arguments.length != 2) {
                    System.out.println("Usage: /lookup <username>");
                    continue;
                }

                String targetUsername = arguments[1];
                if (targetUsername.equals(username)) {
                    System.out.println("Cannot connect to yourself");
                    continue;
                }

                Optional<InetSocketAddress> result = requestPeerLookup(
                    input,
                    output,
                    targetUsername,
                    nextRequestId()
                );

                if (result.isEmpty()) {
                    System.out.println(targetUsername + " is offline");
                } else {
                    connectToPeer(targetUsername, result.get());
                }
                continue;
            }

            byte[]data = message.getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_CHAT_BYTES) {
                System.err.println("Message exceeds limit: " + MAX_CHAT_BYTES);
                continue;
            }

            String targetUsername = selectedPeerUsername;
            PeerConnection connection = targetUsername == null ? null : peers.get(targetUsername);
            if (connection == null) {
                System.out.println("Connect to a peer before sending messages.");
                continue;
            }

            try {
                connection.sendMessage(message);
            } catch (IOException e) {
                System.err.println("Cannot send message: " + e.getMessage());
                closePeer(connection);
            }
        }
    }

    private void requestUserList(InputStream input, OutputStream output, int requestId)
            throws IOException {
        Packet request = new Packet(OpCode.USER_LIST_REQUEST, requestId, new byte[0]);

        PacketCodec.write(output, request);

        Packet response = PacketCodec.read(input);
        if (response == null
                || response.op() != OpCode.USER_LIST_RESPONSE
                || response.requestId() != requestId) {
            throw new IOException("Invalid or missing USER_LIST_RESPONSE");
        }

        String usernames = new String(response.data(), StandardCharsets.UTF_8);
        System.out.println("Online users:");

        if (usernames.isEmpty()) {
            System.out.println("(none)");
            return;
        }

        for (String username : usernames.split("\n")) {
            System.out.println("- " + username);
        }
    }

    private boolean login(InputStream input, OutputStream output, Scanner scanner, int requestId, int peerPort) throws IOException {
        System.out.print("Username: ");
        if (!scanner.hasNextLine())
            return false;
        String username = scanner.nextLine().trim();

        System.out.print("Password: ");
        if (!scanner.hasNextLine())
            return false;
        String password = scanner.nextLine();

        if (username.isEmpty() || password.isEmpty()) {
            System.err.println("Username and password must not be empty");
            return false;
        }

        byte[] credentials = (username + "\n" + password + "\n" + peerPort).getBytes(StandardCharsets.UTF_8);
        Packet auth_request = new Packet(OpCode.AUTH_REQUEST, requestId, credentials);
        PacketCodec.write(output, auth_request);

        Packet auth_response = PacketCodec.read(input);
        if (auth_response == null
                || auth_response.op() != OpCode.AUTH_RESPONSE
                || auth_response.requestId() != requestId) {
            System.err.println("Invalid or missing AUTH_RESPONSE");
            return false;
        }

        String status = new String(auth_response.data(), StandardCharsets.UTF_8);
        if (!status.equals("OK")) {
            System.err.println("Login failed: " + status);
            return false;
        }

        System.out.println("Login successful!");
        this.username = username;
        return true;
    }

    private Optional<InetSocketAddress> requestPeerLookup(InputStream input, OutputStream output, String targetUsername, int requestId) throws IOException {
        Packet request = new Packet(OpCode.PEER_LOOKUP_REQUEST, requestId, targetUsername.getBytes(StandardCharsets.UTF_8));
        PacketCodec.write(output, request);
        Packet response = PacketCodec.read(input);
        if (response == null
            || response.op() != OpCode.PEER_LOOKUP_RESPONSE
            || response.requestId() != requestId) {
            throw new IOException("Invalid or missing PEER_LOOKUP_RESPONSE");
        }

        String payload = new String(response.data(), StandardCharsets.UTF_8);
        if (payload.equals("USER_OFFLINE")) return Optional.empty();
        
        String[] parts = payload.split("\n", -1);
        if (parts.length != 3
            || !parts[0].equals("OK")
            || parts[1].isBlank()) {
            throw new IOException("Invalid peer address payload");
        }

        int peerPort;
        try {
            peerPort = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid peer port", e);
        }

        if (peerPort < 1 || peerPort > 65535) {
            throw new IOException("Peer port is out of range");
        }

        InetSocketAddress address = new InetSocketAddress(
            parts[1],
            peerPort
        );
        return Optional.of(address);
    }

    private void connectToPeer(String targetUsername, InetSocketAddress address) {
        Socket peerSocket = new Socket();
        PeerConnection connection = new PeerConnection(peerSocket); 

        try {
            peerSocket.connect(address, 10000);
            connection.initiateHandshake(username, 1);

            registerPeer(targetUsername, connection);
            selectedPeerUsername = targetUsername;

            System.out.println("Now chatting with " + targetUsername);
        } catch (IOException e) {
            closePeer(connection);

            System.err.println(
                "Cannot connect to " + targetUsername
                    + ": " + e.getMessage()
            );
        }
    }

    private synchronized void registerPeer(String peerUsername, PeerConnection connection) {
        if (closingPeers) {
            closePeer(connection);
            return;
        }
        
        PeerConnection previous = peers.put(peerUsername, connection);
        if (previous != null) {
            closePeer(previous);
        }
        if (selectedPeerUsername == null) {
            selectedPeerUsername = peerUsername;
        }

        Thread.startVirtualThread(() -> {
            try {
                connection.readMessages(peerUsername);
            } catch (IOException e) {
                System.err.println("Peer " + peerUsername + ": " + e.getMessage());
            } finally {
                synchronized (Client.this) {
                    if (peers.remove(peerUsername, connection)
                        && peerUsername.equals(selectedPeerUsername)) {
                        selectedPeerUsername = null;
                    }
                }

                closePeer(connection);
            }
        });
    }

    private void closePeer(PeerConnection connection) {
        try {
            connection.close();
        } catch (IOException e) {
            System.err.println("Cannot close peer: " + e.getMessage());
        }
    }

    private synchronized void closePeers() {
        closingPeers = true;

        for (PeerConnection connection : peers.values()) {
            closePeer(connection);
        }

        peers.clear();
        selectedPeerUsername = null;
    }

    public ServerInfo discover() throws IOException {
        final int DISCOVERY_PORT = 41234;
        final int maxAttemps = 5;

        InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
        int requestId = nextRequestId();
        Packet request = new Packet(OpCode.DISCOVER_REQUEST, requestId, new byte[0]);

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        PacketCodec.write(encoded, request);
        byte[] requestBytes = encoded.toByteArray();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(3000);

            DatagramPacket outgoing = new DatagramPacket(
                requestBytes,
                requestBytes.length,
                broadcastAddress,
                DISCOVERY_PORT
            );

            byte[] buffer = new byte[4096];
            for (int attemp = 1; attemp <= maxAttemps; attemp++) {
                socket.send(outgoing);
                System.out.println("Finding server: " + attemp + "/" + maxAttemps);

                DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);

                try {
                    socket.receive(incoming);
                } catch (SocketTimeoutException e) {
                    break;
                }

                try {
                    ByteArrayInputStream input = new ByteArrayInputStream(
                        incoming.getData(),
                        incoming.getOffset(),
                        incoming.getLength()
                    );

                    Packet response = PacketCodec.read(input);
                    if (response == null
                        || input.available() != 0
                        || response.op() != OpCode.DISCOVER_RESPONSE
                        || response.requestId() != requestId
                        || incoming.getPort() != DISCOVERY_PORT) {
                        continue;
                    }

                    int tcpPort = Integer.parseInt(new String(response.data(), StandardCharsets.UTF_8));
                    if (tcpPort < 1 || tcpPort > 65535) continue;

                    return new ServerInfo(incoming.getAddress().getHostAddress(), tcpPort);
                } catch (IOException | NumberFormatException e) {
                    continue;
                }
            }
        }
        return null;
    }
}
