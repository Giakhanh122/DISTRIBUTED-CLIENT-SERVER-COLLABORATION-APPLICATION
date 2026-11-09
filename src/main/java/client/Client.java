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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import protocols.FileTransferMeta;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import transfer.FileReceiver;
import transfer.FileSender;
import transfer.FileTransferManager;
import transfer.FileTransferSession;

public class Client {

    private String username;
    private final ConcurrentHashMap<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private volatile String selectedPeerUsername;
    private volatile String pendingLookupUser;
    private boolean closingPeers;
    private int requestSequence = 0;

    private final FileTransferManager transferManager = new FileTransferManager();
    private String downloadDir = "downloads";
    private File downloadUserDir;

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
            System.err.println("Discovery failed: " + e.getMessage());
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
            Thread.startVirtualThread(() -> receiveServerPackets(input));
            readConsole(output, scanner);
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        } finally {
            closePeers();
        }
    }

    private boolean handshake(InputStream input, OutputStream output, int requestId) throws IOException {
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

        System.out.println("Handshake successful!");
        return true;
    }

    private boolean login(InputStream input, OutputStream output, Scanner scanner, int requestId, int peerPort) throws IOException {
        System.out.print("Username: ");
        if (!scanner.hasNextLine()) return false;
        String user = scanner.nextLine().trim();

        System.out.print("Password: ");
        if (!scanner.hasNextLine()) return false;
        String password = scanner.nextLine();

        if (user.isEmpty() || password.isEmpty()) {
            System.err.println("Username and password must not be empty");
            return false;
        }

        this.username = user;
        this.downloadUserDir = new File(downloadDir, user);

        byte[] credentials = (user + "\n" + password + "\n" + peerPort).getBytes(StandardCharsets.UTF_8);
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
        return true;
    }

    private void receiveServerPackets(InputStream input) {
        try {
            Packet packet;
            while ((packet = PacketCodec.read(input)) != null) {
                switch (packet.op()) {
                    case CHAT_MESSAGE -> System.out.println(new String(packet.data(), StandardCharsets.UTF_8));
                    case USER_LIST_RESPONSE -> printUserList(packet.data());
                    case PEER_LOOKUP_RESPONSE -> handlePeerLookupResponse(packet.data());
                    case FILE_OFFER -> handleIncomingOffer(packet.data());
                    case FILE_DECISION -> handleIncomingDecision(packet.data());
                    default -> {}
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void printUserList(byte[] data) {
        String usernames = new String(data, StandardCharsets.UTF_8);
        System.out.println("Online users:");
        if (usernames.isEmpty()) {
            System.out.println("(none)");
            return;
        }
        for (String user : usernames.split("\n")) {
            System.out.println("- " + user);
        }
    }

    private void handlePeerLookupResponse(byte[] data) {
        String payload = new String(data, StandardCharsets.UTF_8);
        if (payload.equals("USER_OFFLINE")) {
            System.out.println(pendingLookupUser + " is offline");
            return;
        }
        String[] parts = payload.split("\n", -1);
        if (parts.length >= 3 && parts[0].equals("OK")) {
            try {
                int port = Integer.parseInt(parts[2]);
                InetSocketAddress address = new InetSocketAddress(parts[1], port);
                connectToPeer(pendingLookupUser, address);
            } catch (Exception e) {
                System.err.println("Failed to connect to peer: " + e.getMessage());
            }
        }
    }

    private void handleIncomingOffer(byte[] data) {
        try {
            FileTransferMeta meta = FileTransferMeta.fromBytes(data);
            transferManager.registerSession(new FileTransferSession(meta));
            System.out.printf("%n[FILE OFFER] %s wants to send '%s' (%d bytes). ID: %s%n",
                    meta.sender(), meta.fileName(), meta.fileSize(), meta.transferId());
            System.out.println("Type '/accept' or '/accept " + meta.fileName() + "' or '/reject'");
        } catch (Exception e) {
            System.err.println("Failed to parse file offer: " + e.getMessage());
        }
    }

    private void handleIncomingDecision(byte[] data) {
        String payload = new String(data, StandardCharsets.UTF_8);
        String[] parts = payload.split("\n", -1);
        String transferId = parts[0];
        String action = parts[2];

        if ("ACCEPT".equalsIgnoreCase(action)) {
            String ip = parts.length > 3 ? parts[3] : "127.0.0.1";
            int port = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
            System.out.printf("%n[FILE] Transfer %s ACCEPTED. Sending P2P to %s:%d...%n", transferId, ip, port);
            File file = transferManager.getOutboundFile(transferId);
            if (file != null && port > 0) {
                Thread.startVirtualThread(() -> sendP2P(file, transferId, ip, port));
            }
        } else {
            String reason = parts.length > 3 ? parts[3] : "Declined";
            System.out.printf("%n[FILE] Transfer %s REJECTED: %s%n", transferId, reason);
            transferManager.getSession(transferId).ifPresent(FileTransferSession::reject);
        }
    }

    private void sendP2P(File file, String transferId, String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            FileSender sender = new FileSender(file, transferId, socket.getOutputStream());
            String checksum = sender.send();
            System.out.printf("%n[P2P SENT] %s sent! SHA-256: %s%n", file.getName(), checksum);

            Packet result = PacketCodec.read(socket.getInputStream());
            if (result != null && result.op() == OpCode.FILE_RESULT) {
                System.out.println("[P2P RESULT] " + new String(result.data(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            System.err.println("\n[P2P SEND ERROR] " + e.getMessage());
        }
    }

    private void readConsole(OutputStream output, Scanner scanner) throws IOException {
        System.out.println("Commands: /users, /lookup <user>, /sendfile <user> <path>, /accept [name], /reject [name], /logout");

        while (scanner.hasNextLine()) {
            String message = scanner.nextLine();
            String command = message.trim();

            if (command.equalsIgnoreCase("/logout")) return;
            if (message.isBlank()) continue;

            if (command.equalsIgnoreCase("/users")) {
                PacketCodec.write(output, new Packet(OpCode.USER_LIST_REQUEST, nextRequestId(), new byte[0]));
                continue;
            }

            if (command.startsWith("/lookup ")) {
                String targetUsername = command.substring(8).trim();
                if (targetUsername.equals(username)) {
                    System.out.println("Cannot connect to yourself");
                    continue;
                }
                this.pendingLookupUser = targetUsername;
                PacketCodec.write(output, new Packet(OpCode.PEER_LOOKUP_REQUEST, nextRequestId(), targetUsername.getBytes(StandardCharsets.UTF_8)));
                continue;
            }

            if (command.startsWith("/sendfile")) {
                sendFileOffer(output, command.length() > 9 ? command.substring(9).trim() : "", nextRequestId());
                continue;
            }

            if (command.startsWith("/accept")) {
                acceptOffer(output, command.length() > 7 ? command.substring(7).trim() : "", nextRequestId());
                continue;
            }

            if (command.startsWith("/reject")) {
                rejectOffer(output, command.length() > 7 ? command.substring(7).trim() : "", nextRequestId());
                continue;
            }

            if (selectedPeerUsername == null) {
                System.out.println("Use /lookup <username> to start chatting");
                continue;
            }

            PeerConnection peer = peers.get(selectedPeerUsername);
            if (peer != null) {
                peer.sendMessage(message);
            } else {
                System.out.println("Not connected to " + selectedPeerUsername);
            }
        }
    }

    private void sendFileOffer(OutputStream output, String args, int reqId) throws IOException {
        if (transferManager.hasOngoingTransfer()) {
            System.out.println("Another transfer is ongoing. Please wait.");
            return;
        }

        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) {
            System.out.println("Usage: /sendfile <user> <path>");
            return;
        }

        File file = new File(parts[1]);
        if (!file.exists() || !file.isFile()) {
            System.err.println("File not found: " + file.getAbsolutePath());
            return;
        }

        FileTransferMeta meta = FileTransferMeta.create(username, parts[0], file.getName(), file.length());
        transferManager.registerSession(new FileTransferSession(meta));
        transferManager.registerOutboundFile(meta.transferId(), file);

        PacketCodec.write(output, new Packet(OpCode.FILE_OFFER, reqId, meta.toBytes()));
        System.out.println("[FILE] Offer sent. ID: " + meta.transferId());
    }

    private void acceptOffer(OutputStream output, String query, int reqId) throws IOException {
        var opt = transferManager.findPending(query);
        if (opt.isEmpty()) {
            System.out.println("No matching pending file offer found.");
            return;
        }

        FileTransferSession session = opt.get();
        session.accept();

        ServerSocket listener = new ServerSocket(0);
        int localPort = listener.getLocalPort();
        File targetDir = downloadUserDir != null ? downloadUserDir : new File(downloadDir);

        Thread.startVirtualThread(() -> {
            try (listener; Socket socket = listener.accept()) {
                FileReceiver receiver = new FileReceiver(socket.getInputStream(), socket.getOutputStream(), targetDir, session.meta());
                File saved = receiver.receive();
                System.out.println("\n[P2P RECEIVED] Saved: " + saved.getAbsolutePath());
            } catch (Exception e) {
                System.err.println("\n[P2P RECEIVE ERROR] " + e.getMessage());
            }
        });

        String payload = session.transferId() + "\n" + session.meta().sender() + "\nACCEPT\n" + localPort;
        PacketCodec.write(output, new Packet(OpCode.FILE_DECISION, reqId, payload.getBytes(StandardCharsets.UTF_8)));
        System.out.println("[FILE] Accepted '" + session.meta().fileName() + "'. Waiting for sender on port " + localPort + "...");
    }

    private void rejectOffer(OutputStream output, String query, int reqId) throws IOException {
        var opt = transferManager.findPending(query);
        if (opt.isEmpty()) {
            System.out.println("No matching pending file offer found.");
            return;
        }

        FileTransferSession session = opt.get();
        session.reject();

        String payload = session.transferId() + "\n" + session.meta().sender() + "\nREJECT\nDeclined by user";
        PacketCodec.write(output, new Packet(OpCode.FILE_DECISION, reqId, payload.getBytes(StandardCharsets.UTF_8)));
        System.out.println("[FILE] Rejected '" + session.meta().fileName() + "'");
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
            System.err.println("Cannot connect to " + targetUsername + ": " + e.getMessage());
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
