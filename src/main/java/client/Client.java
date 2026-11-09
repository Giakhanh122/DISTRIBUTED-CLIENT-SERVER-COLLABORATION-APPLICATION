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
    private String downloadDir = "downloads";
    private File downloadUserDir;

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
        this.downloadUserDir = new File(downloadDir, username);
        byte[] credentials = (username + "\n" + password).getBytes(StandardCharsets.UTF_8);
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
        for (String username : usernames.split("\n")) {
            System.out.println("- " + username);
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

    private void readConsole(OutputStream output, Scanner scanner, int requestId) throws IOException {
        System.out.println("Type a message, /users, /sendfile <user> <path>, /accept [name], /reject [name], or /logout");

        while (scanner.hasNextLine()) {
            String message = scanner.nextLine();
            String command = message.trim();

            if (command.equalsIgnoreCase("/logout")) return;
            if (message.isBlank()) continue;

            if (command.equalsIgnoreCase("/users")) {
                PacketCodec.write(output, new Packet(OpCode.USER_LIST_REQUEST, ++requestId, new byte[0]));
            } else if (command.startsWith("/sendfile")) {
                sendFileOffer(output, command.length() > 9 ? command.substring(9).trim() : "", ++requestId);
            } else if (command.startsWith("/accept")) {
                acceptOffer(output, command.length() > 7 ? command.substring(7).trim() : "", ++requestId);
            } else if (command.startsWith("/reject")) {
                rejectOffer(output, command.length() > 7 ? command.substring(7).trim() : "", ++requestId);
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

        FileTransferMeta meta = FileTransferMeta.create(currentUsername, parts[0], file.getName(), file.length());
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
}
