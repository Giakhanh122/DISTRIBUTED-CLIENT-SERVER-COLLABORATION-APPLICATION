package client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;

public class Client {

    private String ipAddress;
    private int portNumber;

    private static final int MAX_CHAT_BYTES = 64 * 1024;

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
            readConsole(input, output, scanner, requestId);
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
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

    private void readConsole(InputStream input, OutputStream output, Scanner scanner, int requestId)
            throws IOException {
        System.out.println("Type a message, /users to list online users, or logout to disconnect.");

        while (scanner.hasNextLine()) {
            String message = scanner.nextLine();
            String command = message.trim();

            if (command.equalsIgnoreCase("logout"))
                return;
            if (message.isBlank())
                continue;

            if (command.equalsIgnoreCase("/users")) {
                requestUserList(input, output, ++requestId);
                continue;
            }

            byte[] data = message.getBytes(StandardCharsets.UTF_8);

            if (data.length > MAX_CHAT_BYTES) {
                System.err.println("Message exceeds limit: " + MAX_CHAT_BYTES);
                continue;
            }

            Packet chatMessage = new Packet(OpCode.CHAT_MESSAGE, ++requestId, data);
            PacketCodec.write(output, chatMessage);
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

    private boolean login(InputStream input, OutputStream output, Scanner scanner, int requestId)
            throws IOException {
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
}
