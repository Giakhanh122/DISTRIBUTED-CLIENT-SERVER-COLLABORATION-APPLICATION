package client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
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

    private String currentUsername;
    private final FileTransferManager transferManager = new FileTransferManager();
    private final File downloadDir = new File("downloads");

    public void connectToServer(String host, int portNumber, Scanner scanner) {
        try (Socket socket = new Socket(host, portNumber)) {
            socket.setSoTimeout(10000);

            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            int requestId = 1;

            if (!handshake(input, output, requestId)) {
                return;
            }

            if (!login(input, output, scanner, ++requestId)) {
                return;
            }

            socket.setSoTimeout(0);
            Thread.startVirtualThread(() -> receivePackets(input));
            readConsole(output, scanner, requestId);
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
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

    private boolean login(InputStream input, OutputStream output, Scanner scanner, int requestId) throws IOException {
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

        this.currentUsername = username;
        byte[] credentials = (username + "\n" + password).getBytes(StandardCharsets.UTF_8);
        Packet authRequest = new Packet(OpCode.AUTH_REQUEST, requestId, credentials);
        PacketCodec.write(output, authRequest);

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
        return true;
    }

    private void receivePackets(InputStream input) {
        try {
            Packet packet;
            while ((packet = PacketCodec.read(input)) != null) {
                switch (packet.op()) {
                    case CHAT_MESSAGE -> System.out.println(new String(packet.data(), StandardCharsets.UTF_8));
                    case USER_LIST_RESPONSE -> printUserList(packet.data());
                    case FILE_OFFER -> handleIncomingOffer(packet.data());
                    case FILE_DECISION -> handleIncomingDecision(packet.data());
                    default -> {}
                }
            }
        } catch (IOException ignored) {
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

    private void handleIncomingOffer(byte[] data) {
        try {
            FileTransferMeta meta = FileTransferMeta.fromBytes(data);
            FileTransferSession session = new FileTransferSession(meta);
            transferManager.registerSession(session);
            System.out.println("\n[FILE OFFER] " + meta.sender() + " wants to send '" + meta.fileName()
                    + "' (" + meta.fileSize() + " bytes). ID: " + meta.transferId());
            System.out.println("Type '/accept " + meta.transferId() + "' or '/reject " + meta.transferId() + "'");
        } catch (Exception e) {
            System.err.println("Failed to read FILE_OFFER: " + e.getMessage());
        }
    }

    private void handleIncomingDecision(byte[] data) {
        String payload = new String(data, StandardCharsets.UTF_8);
        String[] parts = payload.split("\n", -1);
        String transferId = parts[0];
        String action = parts.length > 2 ? parts[2] : parts[1];

        if ("ACCEPT".equalsIgnoreCase(action)) {
            String ip = parts.length > 3 ? parts[3] : "127.0.0.1";
            int port = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
            System.out.println("\n[FILE] Transfer " + transferId + " was ACCEPTED. Starting P2P send to " + ip + ":" + port + "...");
            transferManager.getSession(transferId).ifPresent(FileTransferSession::accept);
            File outboundFile = transferManager.getOutboundFile(transferId);
            if (outboundFile != null && port > 0) {
                Thread.startVirtualThread(() -> runP2PSend(outboundFile, transferId, ip, port));
            }
        } else {
            String reason = parts.length > 3 ? parts[3] : "Declined";
            System.out.println("\n[FILE] Transfer " + transferId + " was REJECTED: " + reason);
            transferManager.getSession(transferId).ifPresent(FileTransferSession::reject);
        }
    }

    private void runP2PSend(File file, String transferId, String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            FileSender sender = new FileSender(file, transferId, socket.getOutputStream());
            String checksum = sender.send();
            System.out.println("\n[P2P SENT] " + file.getName() + " completed! SHA-256: " + checksum);
        } catch (Exception e) {
            System.err.println("\n[P2P SEND ERROR] " + e.getMessage());
        }
    }

    private void readConsole(OutputStream output, Scanner scanner, int requestId) throws IOException {
        System.out.println("Type a message, or commands.\nCommands: /users to list online users \n/sendfile <receiver> <filepath> \n/accept <transfer_id> to accept file \n/reject <transfer_id> to reject file \n/logout to disconnect");

        while (scanner.hasNextLine()) {
            String message = scanner.nextLine();
            String command = message.trim();

            if (command.equalsIgnoreCase("/logout")) return;
            if (message.isBlank()) continue;

            if (command.equalsIgnoreCase("/users")) {
                PacketCodec.write(output, new Packet(OpCode.USER_LIST_REQUEST, ++requestId, new byte[0]));
            } else if (command.startsWith("/sendfile ")) {
                sendFileOffer(output, command.substring(10).trim(), ++requestId);
            } else if (command.startsWith("/accept ")) {
                acceptFileOffer(output, command.substring(8).trim(), ++requestId);
            } else if (command.startsWith("/reject ")) {
                sendDecision(output, command.substring(8).trim(), "REJECT", 0, ++requestId);
            } else {
                byte[] data = message.getBytes(StandardCharsets.UTF_8);
                if (data.length > MAX_CHAT_BYTES) {
                    System.err.println("Message exceeds limit: " + MAX_CHAT_BYTES);
                    continue;
                }
                PacketCodec.write(output, new Packet(OpCode.CHAT_MESSAGE, ++requestId, data));
            }
        }
    }

    private void sendFileOffer(OutputStream output, String args, int reqId) throws IOException {
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

        FileTransferMeta meta = FileTransferMeta.create(currentUsername, parts[0], file.getName(), file.length());
        FileTransferSession session = new FileTransferSession(meta);
        transferManager.registerSession(session);
        transferManager.registerOutboundFile(meta.transferId(), file);

        PacketCodec.write(output, new Packet(OpCode.FILE_OFFER, reqId, meta.toBytes()));
        System.out.println("[FILE] Offer sent. Transfer ID: " + meta.transferId());
    }

    private void acceptFileOffer(OutputStream output, String transferId, int reqId) throws IOException {
        var optSession = transferManager.getSession(transferId);
        if (optSession.isEmpty()) {
            System.out.println("Session not found: " + transferId);
            return;
        }

        FileTransferSession session = optSession.get();
        session.accept();

        ServerSocket p2pListener = new ServerSocket(0);
        int localPort = p2pListener.getLocalPort();

        Thread.startVirtualThread(() -> {
            try (ServerSocket listener = p2pListener; Socket peerSocket = listener.accept()) {
                FileReceiver receiver = new FileReceiver(peerSocket.getInputStream(), peerSocket.getOutputStream(), downloadDir, session.meta(), session, null);
                File savedFile = receiver.receive();
                System.out.println("\n[P2P RECEIVED] Saved to: " + savedFile.getAbsolutePath());
            } catch (Exception e) {
                System.err.println("\n[P2P RECEIVE ERROR] " + e.getMessage());
            }
        });

        sendDecision(output, transferId, "ACCEPT", localPort, reqId);
        System.out.println("[FILE] Accepted transfer. Waiting for P2P connection on port " + localPort + "...");
    }

    private void sendDecision(OutputStream output, String transferId, String decision, int p2pPort, int reqId) throws IOException {
        var optSession = transferManager.getSession(transferId);
        if (optSession.isEmpty()) {
            System.out.println("Session not found: " + transferId);
            return;
        }

        FileTransferSession session = optSession.get();
        if ("REJECT".equals(decision)) {
            session.reject();
        }

        String payload = transferId + "\n" + session.meta().sender() + "\n" + decision + "\n" + p2pPort;
        PacketCodec.write(output, new Packet(OpCode.FILE_DECISION, reqId, payload.getBytes(StandardCharsets.UTF_8)));
    }
}
