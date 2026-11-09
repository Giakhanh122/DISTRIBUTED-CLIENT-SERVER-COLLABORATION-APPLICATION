package client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import protocols.PacketValidator;

public class Client {

    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private String username;
    private int peerPort;
    private final ConcurrentHashMap<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private volatile String selectedPeerUsername;
    private boolean closingPeers;
    private int requestSequence = 0;
    private final ConcurrentHashMap<Integer, CompletableFuture<Packet>> pendingResponses = new ConcurrentHashMap<>();

    private FileTransferController fileTransfers;

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
            PeerListener peerListener = new PeerListener(this::registerPeer,
                    (fileSocket, hello) -> fileTransfers.receiveFileConnection(fileSocket, hello));
        ) {
            socket.setSoTimeout(10000);

            peerPort = peerListener.getPort();
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            if (!handshake(input, output, nextRequestId())) {
                return;
            }

            if (!login(input, output, scanner, nextRequestId(), peerPort)) {
                return;
            }

            fileTransfers = new FileTransferController(username, peerPort, new File("downloads"), peers);
            Thread.startVirtualThread(peerListener);
            socket.setSoTimeout(0);
            Thread.startVirtualThread(() -> receivePackets(input));
            readConsole(output, scanner);
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        } finally {
            if (fileTransfers != null) {
                fileTransfers.close();
            }
            closePeers();
        }
    }

    private boolean handshake(InputStream input, OutputStream output, int requestId) throws IOException {
        Packet hello = new Packet(OpCode.HELLO, requestId, new byte[0]);
        PacketCodec.write(output, hello);

        Packet response = PacketCodec.read(input);
        try {
            PacketValidator.validateResponse(response, OpCode.HELLO_ACK, requestId);
            if (response.payloadLength() != 0) {
                throw new IOException("HELLO_ACK payload must be empty");
            }
        } catch (IOException e) {
            System.err.println("Invalid or missing HELLO_ACK");
            return false;
        }

        System.out.println("Handshake successful!");
        return true;
    }

    private void readConsole(OutputStream output, Scanner scanner) {
        System.out.println(
            "Commands: /users, /lookup <username>, /sendfile <receiver> <filepath>, "
                + "/accept <offer_number>, /reject <offer_number>, /logout"
        );

        while (scanner.hasNextLine()) {
            String message = scanner.nextLine();
            String command = message.trim();
            if (command.equalsIgnoreCase("/logout"))
                return;
            if (message.isBlank())
                continue;

            if (command.equalsIgnoreCase("/users")) {
                try {
                    requestUserList(output, nextRequestId());
                } catch (IOException e) {
                    System.err.println("Server command: " + e.getMessage());
                }
                continue;
            }

            try {
                if (command.startsWith("/sendfile ")) {
                    sendFileOffer(command.substring(10).trim(), nextRequestId());
                    continue;
                }
                if (command.startsWith("/accept ")) {
                    fileTransfers.sendDecision(command.substring(8).trim(), "ACCEPT", nextRequestId());
                    continue;
                }
                if (command.startsWith("/reject ")) {
                    fileTransfers.sendDecision(command.substring(8).trim(), "REJECT", nextRequestId());
                    continue;
                }
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                System.err.println("File command: " + e.getMessage());
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

                try {
                    Optional<InetSocketAddress> result = requestPeerLookup(
                        output,
                        targetUsername,
                        nextRequestId()
                    );

                    if (result.isEmpty()) {
                        System.out.println(targetUsername + " is offline");
                    } else {
                        connectToPeer(targetUsername, result.get());
                    }
                } catch (IOException e) {
                    System.err.println("Server command: " + e.getMessage());
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

    private void requestUserList(OutputStream output, int requestId)
            throws IOException {
        Packet request = new Packet(OpCode.USER_LIST_REQUEST, requestId, new byte[0]);
        Packet response = awaitResponse(output, request, OpCode.USER_LIST_RESPONSE);
        printUserList(response.text());
    }

    private boolean login(InputStream input, OutputStream output, Scanner scanner, int requestId, int peerPort) throws IOException {
        System.out.print("Username: ");
        if (!scanner.hasNextLine()) return false;
        String username = scanner.nextLine().trim();

        System.out.print("Password: ");
        if (!scanner.hasNextLine()) return false;
        String password = scanner.nextLine();

        if (username.isEmpty() || password.isEmpty()) {
            System.err.println("Username and password must not be empty");
            return false;
        }

        Packet auth_request = Packet.text(
                OpCode.AUTH_REQUEST, requestId, username + "\n" + password + "\n" + peerPort);
        PacketCodec.write(output, auth_request);

        Packet authResponse = PacketCodec.read(input);
        try {
            PacketValidator.validateResponse(authResponse, OpCode.AUTH_RESPONSE, requestId);
        } catch (IOException e) {
            System.err.println("Invalid or missing AUTH_RESPONSE");
            return false;
        }

        String status = authResponse.text();
        if (!status.equals("OK")) {
            System.err.println("Login failed: " + status);
            return false;
        }

        System.out.println("Login successful!");
        this.username = username;
        return true;
    }

    private Optional<InetSocketAddress> requestPeerLookup(OutputStream output, String targetUsername, int requestId) throws IOException {
        Packet request = Packet.text(OpCode.PEER_LOOKUP_REQUEST, requestId, targetUsername);
        Packet response = awaitResponse(output, request, OpCode.PEER_LOOKUP_RESPONSE);

        String payload = response.text();
        if (payload.equals("USER_OFFLINE")) return Optional.empty();

        return Optional.of(parsePeerAddress(payload));
    }

    private InetSocketAddress parsePeerAddress(String payload) throws IOException {
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

        return new InetSocketAddress(parts[1], peerPort);
    }

    private void connectToPeer(String targetUsername, InetSocketAddress address) {
        Socket peerSocket = new Socket();
        PeerConnection connection = new PeerConnection(peerSocket); 

        try {
            peerSocket.connect(address, 10000);
            connection.initiateHandshake(username, 1, peerPort);

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
                connection.readMessages(peerUsername, packet -> {
                    try {
                        if (packet.op() == OpCode.FILE_OFFER) {
                            fileTransfers.handleIncomingOffer(peerUsername, connection, packet);
                        } else {
                            fileTransfers.handleIncomingDecision(connection, packet);
                        }
                    } catch (IOException | RuntimeException e) {
                        System.err.println("Invalid peer file packet: " + e.getMessage());
                    }
                });
            } catch (IOException e) {
                System.err.println("Peer " + peerUsername + ": " + e.getMessage());
            } finally {
                fileTransfers.closeForPeer(connection);
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
                    continue;
                }

                try {
                    ByteArrayInputStream input = new ByteArrayInputStream(
                        incoming.getData(),
                        incoming.getOffset(),
                        incoming.getLength()
                    );

                    Packet response = PacketCodec.read(input);
                    PacketValidator.validateResponse(response, OpCode.DISCOVER_RESPONSE, requestId);
                    if (input.available() != 0 || incoming.getPort() != DISCOVERY_PORT) {
                        continue;
                    }

                    int tcpPort = Integer.parseInt(response.text());
                    if (tcpPort < 1 || tcpPort > 65535) continue;

                    return new ServerInfo(incoming.getAddress().getHostAddress(), tcpPort);
                } catch (IOException | NumberFormatException e) {
                    continue;
                }
            }
        }
        return null;
    }

    private Packet awaitResponse(OutputStream output, Packet request, OpCode expectedOp) throws IOException {
        CompletableFuture<Packet> responseFuture = new CompletableFuture<>();
        pendingResponses.put(request.requestId(), responseFuture);
        try {
            PacketCodec.write(output, request);
            Packet response = responseFuture.get(10, TimeUnit.SECONDS);
            return PacketValidator.validateResponse(response, expectedOp, request.requestId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + expectedOp, e);
        } catch (TimeoutException e) {
            throw new IOException("Timed out waiting for " + expectedOp, e);
        } catch (ExecutionException e) {
            throw new IOException("Failed while waiting for " + expectedOp, e.getCause());
        } finally {
            pendingResponses.remove(request.requestId(), responseFuture);
        }
    }

    private void receivePackets(InputStream input) {
        try {
            Packet packet;
            while ((packet = PacketCodec.read(input)) != null) {
                switch (packet.op()) {
                    case CHAT_MESSAGE -> System.out.println(packet.text());
                    case USER_LIST_RESPONSE, PEER_LOOKUP_RESPONSE -> {
                        CompletableFuture<Packet> responseFuture = pendingResponses.get(packet.requestId());
                        if (responseFuture != null) {
                            responseFuture.complete(packet);
                        }
                    }
                    default -> {}
                }
            }
        } catch (IOException e) {
            System.err.println("Server receive error: " + e.getMessage());
        } finally {
            IOException closed = new IOException("Server connection closed");
            pendingResponses.values().forEach(future -> future.completeExceptionally(closed));
        }
    }

    private void printUserList(String usernames) {
        System.out.println("Online users:");
        if (usernames.isEmpty()) {
            System.out.println("(none)");
        } else {
            for (String username : usernames.split("\n")) {
                System.out.println("- " + username);
            }
        }
    }

    private void sendFileOffer(String args, int requestId) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length != 2) {
            System.out.println("Usage: /sendfile <user> <path>");
            return;
        }
        PeerConnection peer = peers.get(parts[0]);
        fileTransfers.offer(peer, parts[0], new File(parts[1]), requestId);
    }
}
