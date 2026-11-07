package client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import protocols.FileTransferMeta;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import transfer.FileTransferManager;
import transfer.FileTransferSession;

public class Client {

    private static final int MAX_CHAT_BYTES = 64 * 1024;

    private String currentUsername;
    private final FileTransferManager transferManager = new FileTransferManager();

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
            String ip = parts.length > 3 ? parts[3] : "unknown";
            String port = parts.length > 4 ? parts[4] : "0";
            System.out.println("\n[FILE] Transfer " + transferId + " was ACCEPTED by recipient (Peer: " + ip + ":" + port + ").");
            transferManager.getSession(transferId).ifPresent(FileTransferSession::accept);
        } else {
            String reason = parts.length > 3 ? parts[3] : "Declined";
            System.out.println("\n[FILE] Transfer " + transferId + " was REJECTED: " + reason);
            transferManager.getSession(transferId).ifPresent(FileTransferSession::reject);
        }
    }

    private void readConsole(OutputStream output, Scanner scanner, int requestId) throws IOException {
        System.out.println("Commands: /users | /sendfile <user> <path> | /accept <id> | /reject <id> | /logout");

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
                sendDecision(output, command.substring(8).trim(), "ACCEPT", ++requestId);
            } else if (command.startsWith("/reject ")) {
                sendDecision(output, command.substring(8).trim(), "REJECT", ++requestId);
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

    private void sendDecision(OutputStream output, String transferId, String decision, int reqId) throws IOException {
        var optSession = transferManager.getSession(transferId);
        if (optSession.isEmpty()) {
            System.out.println("Session not found: " + transferId);
            return;
        }

        FileTransferSession session = optSession.get();
        if ("ACCEPT".equals(decision)) {
            session.accept();
        } else {
            session.reject();
        }

        String payload = transferId + "\n" + session.meta().sender() + "\n" + decision;
        PacketCodec.write(output, new Packet(OpCode.FILE_DECISION, reqId, payload.getBytes(StandardCharsets.UTF_8)));
        System.out.println("[FILE] Sent " + decision + " for transfer " + transferId);
    }
}
