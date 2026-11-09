package client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
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
import java.util.Arrays;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import protocols.FileTransferMeta;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import transfer.FileReceiver;
import transfer.FileSender;
import transfer.FileTransferManager;
import transfer.FileTransferSession;

public class Client {

    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private static final int TRANSFER_TIMEOUT_MS = 30000;
    private String username;
    private int peerPort;
    private final ConcurrentHashMap<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private volatile String selectedPeerUsername;
    private boolean closingPeers;
    private int requestSequence = 0;
    private final FileTransferManager transferManager = new FileTransferManager();
    private final File downloadDir = new File("downloads");
    private final ConcurrentHashMap<Integer, CompletableFuture<Packet>> pendingResponses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Closeable> transferConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PeerConnection> transferPeers = new ConcurrentHashMap<>();
    private volatile boolean closingTransfers;

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
            PeerListener peerListener = new PeerListener(this::registerPeer, this::receiveFileConnection);
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

            Thread.startVirtualThread(peerListener);
            socket.setSoTimeout(0);
            Thread.startVirtualThread(() -> receivePackets(input));
            readConsole(output, scanner);
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        } finally {
            closeTransfers();
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

    private void readConsole(OutputStream output, Scanner scanner) throws IOException {
        System.out.println(
            "Commands: /users, /lookup <username>, /sendfile <receiver> <filepath>, "
                + "/accept <transfer_id>, /reject <transfer_id>, /logout"
        );

        while (scanner.hasNextLine()) {
            String message = scanner.nextLine();
            String command = message.trim();
            if (command.equalsIgnoreCase("/logout"))
                return;
            if (message.isBlank())
                continue;

            if (command.equalsIgnoreCase("/users")) {
                requestUserList(output, nextRequestId());
                continue;
            }

            try {
                if (command.startsWith("/sendfile ")) {
                    sendFileOffer(command.substring(10).trim(), nextRequestId());
                    continue;
                }
                if (command.startsWith("/accept ")) {
                    sendDecision(command.substring(8).trim(), "ACCEPT", nextRequestId());
                    continue;
                }
                if (command.startsWith("/reject ")) {
                    sendDecision(command.substring(8).trim(), "REJECT", nextRequestId());
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
        printUserList(response.data());
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

        byte[] credentials = (username + "\n" + password + "\n" + peerPort).getBytes(StandardCharsets.UTF_8);
        Packet auth_request = new Packet(OpCode.AUTH_REQUEST, requestId, credentials);
        PacketCodec.write(output, auth_request);

        Packet authResponse = PacketCodec.read(input);
        if (authResponse == null
                || authResponse.op() != OpCode.AUTH_RESPONSE
                || authResponse.requestId() != requestId) {
            System.err.println("Invalid or missing AUTH_RESPONSE");
            return false;
        }

        String status = new String(authResponse.data(), StandardCharsets.UTF_8);
        if (!status.equals("OK")) {
            System.err.println("Login failed: " + status);
            return false;
        }

        System.out.println("Login successful!");
        this.username = username;
        return true;
    }

    private Optional<InetSocketAddress> requestPeerLookup(OutputStream output, String targetUsername, int requestId) throws IOException {
        Packet request = new Packet(OpCode.PEER_LOOKUP_REQUEST, requestId, targetUsername.getBytes(StandardCharsets.UTF_8));
        Packet response = awaitResponse(output, request, OpCode.PEER_LOOKUP_RESPONSE);

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
                            handleIncomingOffer(peerUsername, connection, packet.data());
                        } else {
                            handleIncomingDecision(connection, packet.data());
                        }
                    } catch (IOException | RuntimeException e) {
                        System.err.println("Invalid peer file packet: " + e.getMessage());
                    }
                });
            } catch (IOException e) {
                System.err.println("Peer " + peerUsername + ": " + e.getMessage());
            } finally {
                closeTransfersForPeer(connection);
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

    private Packet awaitResponse(OutputStream output, Packet request, OpCode expectedOp) throws IOException {
        CompletableFuture<Packet> responseFuture = new CompletableFuture<>();
        pendingResponses.put(request.requestId(), responseFuture);
        try {
            PacketCodec.write(output, request);
            Packet response = responseFuture.get(10, TimeUnit.SECONDS);
            if (response.op() != expectedOp) {
                throw new IOException("Expected " + expectedOp + ", got " + response.op());
            }
            return response;
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
                    case CHAT_MESSAGE -> System.out.println(new String(packet.data(), StandardCharsets.UTF_8));
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

    private void printUserList(byte[] data) {
        String usernames = new String(data, StandardCharsets.UTF_8);
        System.out.println("Online users:");
        if (usernames.isEmpty()) {
            System.out.println("(none)");
        } else {
            for (String username : usernames.split("\n")) {
                System.out.println("- " + username);
            }
        }
    }

    private void handleIncomingOffer(String peerUsername, PeerConnection peer, byte[] data) throws IOException {
        FileTransferMeta meta = FileTransferMeta.fromBytes(data);
        if (!meta.receiver().equals(username) || !meta.sender().equals(peerUsername)
                || meta.sender().equals(username)) {
            throw new IOException("File offer has invalid participants");
        }
        FileTransferSession session = new FileTransferSession(meta);
        registerTransfer(session, peer, null);
        System.out.println("\n[FILE OFFER] " + meta.sender() + " wants to send '" + meta.fileName()
                + "' (" + meta.fileSize() + " bytes). ID: " + meta.transferId());
        System.out.println("SHA-256: " + meta.checksum());
        System.out.println("Type '/accept " + meta.transferId() + "' or '/reject " + meta.transferId() + "'");
    }

    private synchronized void handleIncomingDecision(PeerConnection peer, byte[] data) throws IOException {
        String[] parts = new String(data, StandardCharsets.UTF_8).split("\n", -1);
        if (parts.length != 2 || !(parts[1].equals("ACCEPT") || parts[1].equals("REJECT"))) {
            throw new IOException("Invalid FILE_DECISION payload");
        }
        FileTransferSession session = transferManager.getSession(parts[0]).orElse(null);
        if (session == null || transferPeers.get(parts[0]) != peer
                || !session.meta().sender().equals(username)
                || session.state() != FileTransferSession.State.OFFERED) {
            throw new IOException("Decision does not match an outgoing offer on this chat connection");
        }
        if (parts[1].equals("ACCEPT")) {
            File file = transferManager.getOutboundFile(session.transferId());
            session.accept();
            System.out.println("\n[FILE] Transfer " + session.transferId() + " was ACCEPTED.");
            Thread.startVirtualThread(() -> runP2PSend(file, session, peer));
        } else {
            session.reject();
            finishTransfer(session);
            System.out.println("\n[FILE] Transfer " + session.transferId() + " was REJECTED: Declined");
        }
    }

    private void runP2PSend(File file, FileTransferSession session, PeerConnection peer) {
        try (Socket socket = new Socket()) {
            trackTransfer(session, socket);
            socket.connect(peer.peerAddress(), 10000);
            socket.setSoTimeout(TRANSFER_TIMEOUT_MS);
            PacketCodec.write(socket.getOutputStream(), new Packet(OpCode.FILE_OFFER, 0, session.meta().toBytes()));
            FileSender sender = new FileSender(file, session.transferId(), socket.getOutputStream(), session, null);
            String checksum = sender.send();
            sender.awaitResult(socket.getInputStream());
            System.out.println("\n[P2P SENT] " + file.getName() + " completed! SHA-256: " + checksum);
        } catch (IOException | RuntimeException e) {
            session.fail();
            System.err.println("\n[P2P SEND ERROR] " + e.getMessage());
        } finally {
            finishTransfer(session);
        }
    }

    private void sendFileOffer(String args, int requestId) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length != 2) {
            System.out.println("Usage: /sendfile <user> <path>");
            return;
        }
        if (parts[0].equals(username)) {
            throw new IllegalArgumentException("Cannot send a file to yourself");
        }
        PeerConnection peer = peers.get(parts[0]);
        if (peer == null || peer.isClosed()) {
            System.out.println("Connect to " + parts[0] + " with /lookup first");
            return;
        }
        File file = new File(parts[1]);
        // Hashing can take time; keep both the console and the chat reader responsive.
        Thread.startVirtualThread(() -> {
            FileTransferSession session = null;
            try {
                long size = file.length();
                String checksum = FileSender.checksum(file);
                if (file.length() != size) {
                    throw new IOException("File changed while preparing offer");
                }
                FileTransferMeta meta = FileTransferMeta.create(username, parts[0], file.getName(), size, checksum);
                session = new FileTransferSession(meta);
                registerTransfer(session, peer, file);
                peer.sendPacket(new Packet(OpCode.FILE_OFFER, requestId, meta.toBytes()));
                System.out.println("[FILE] Offer sent. Transfer ID: " + meta.transferId());
            } catch (IOException | RuntimeException e) {
                if (session != null) {
                    session.fail();
                    finishTransfer(session);
                }
                System.err.println("[FILE OFFER ERROR] " + e.getMessage());
            }
        });
    }

    private synchronized void sendDecision(String transferId, String decision, int requestId) throws IOException {
        FileTransferSession session = transferManager.getSession(transferId).orElse(null);
        if (session == null) {
            System.out.println("Session not found: " + transferId);
            return;
        }
        if (!session.meta().receiver().equals(username) || session.state() != FileTransferSession.State.OFFERED) {
            throw new IllegalStateException("This is not a pending incoming offer");
        }
        PeerConnection peer = transferPeers.get(transferId);
        try {
            if (decision.equals("ACCEPT")) {
                session.accept();
            } else {
                session.reject();
            }
            peer.sendPacket(new Packet(OpCode.FILE_DECISION, requestId,
                    (transferId + "\n" + decision).getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            session.fail();
            finishTransfer(session);
            throw e;
        }
        if (decision.equals("REJECT")) {
            finishTransfer(session);
        } else {
            CompletableFuture.delayedExecutor(TRANSFER_TIMEOUT_MS, TimeUnit.MILLISECONDS).execute(() -> {
                synchronized (Client.this) {
                    if (session.state() == FileTransferSession.State.ACCEPTED) {
                        session.fail();
                        finishTransfer(session);
                        System.err.println("[FILE] Accepted offer expired: " + transferId);
                    }
                }
            });
            System.out.println("[FILE] Accepted transfer " + transferId + ". Waiting on peer port " + peerPort);
        }
    }

    private void receiveFileConnection(Socket socket, Packet hello) {
        FileTransferSession session = null;
        boolean claimed = false;
        try {
            socket.setSoTimeout(TRANSFER_TIMEOUT_MS);
            FileTransferMeta meta = FileTransferMeta.fromBytes(hello.data());
            session = transferManager.getSession(meta.transferId()).orElseThrow(
                    () -> new IOException("Unknown file transfer"));
            claimIncomingTransfer(session, meta, socket);
            claimed = true;
            File saved = new FileReceiver(socket.getInputStream(), socket.getOutputStream(),
                    downloadDir, session.meta(), session, null).receive();
            System.out.println("\n[P2P RECEIVED] Saved to: " + saved.getAbsolutePath());
        } catch (IOException | RuntimeException e) {
            if (claimed) {
                session.fail();
            } else {
                try {
                    PacketCodec.write(socket.getOutputStream(), new Packet(OpCode.FILE_RESULT, 0,
                            ("FAIL: " + e.getMessage()).getBytes(StandardCharsets.UTF_8)));
                } catch (IOException ignored) {
                }
            }
            System.err.println("\n[P2P RECEIVE ERROR] " + e.getMessage());
        } finally {
            if (claimed) {
                finishTransfer(session);
            }
        }
    }

    private synchronized void claimIncomingTransfer(FileTransferSession session, FileTransferMeta meta,
                                                     Socket socket) throws IOException {
        PeerConnection peer = transferPeers.get(session.transferId());
        if (peer == null || peer.isClosed() || peers.get(meta.sender()) != peer
                || !meta.receiver().equals(username)
                || !socket.getInetAddress().equals(peer.peerAddress().getAddress())
                || !Arrays.equals(meta.toBytes(), session.meta().toBytes())
                || session.state() != FileTransferSession.State.ACCEPTED) {
            throw new IOException("Connection does not match an accepted file session");
        }
        trackTransfer(session, socket);
        // Claim atomically, so replayed connections cannot receive into this session twice.
        session.startTransfer();
    }

    private synchronized void registerTransfer(FileTransferSession session, PeerConnection peer, File file)
            throws IOException {
        if (closingTransfers || peer.isClosed() || !peers.containsValue(peer)) {
            throw new IOException("Peer chat connection is closed");
        }
        transferManager.registerSession(session);
        transferPeers.put(session.transferId(), peer);
        if (file != null) {
            transferManager.registerOutboundFile(session.transferId(), file);
        }
    }

    private synchronized void trackTransfer(FileTransferSession session, Socket socket) throws IOException {
        if (closingTransfers || session.isTerminal()
                || transferManager.getSession(session.transferId()).orElse(null) != session
                || transferConnections.putIfAbsent(session.transferId(), socket) != null) {
            throw new IOException("File session is closed or already has a connection");
        }
    }

    private synchronized void finishTransfer(FileTransferSession session) {
        if (transferManager.removeSession(session)) {
            transferConnections.remove(session.transferId());
            transferPeers.remove(session.transferId());
        }
    }

    private synchronized void closeTransfersForPeer(PeerConnection peer) {
        for (FileTransferSession session : transferManager.getActiveSessions()) {
            if (peer == null || transferPeers.get(session.transferId()) == peer) {
                session.cancel();
                Closeable connection = transferConnections.get(session.transferId());
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (IOException ignored) {
                    }
                }
                finishTransfer(session);
            }
        }
    }

    private synchronized void closeTransfers() {
        closingTransfers = true;
        closeTransfersForPeer(null);
    }
}
