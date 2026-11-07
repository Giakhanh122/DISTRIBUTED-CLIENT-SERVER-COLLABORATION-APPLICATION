package client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
        System.out.println("Type a message, /users to list online users, or /logout to disconnect.");

        while (scanner.hasNextLine()) {
            String message = scanner.nextLine();
            String command = message.trim();

            if (command.equalsIgnoreCase("/logout"))
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

    // DHCPDatagramPacket

    class ServerInfo {
        private String address;
        private int port;

        public ServerInfo(String address, int port) {
            this.address = address;
            this.port = port;
        }

        public String getAddress() {
            return address;
        }

        public int getPort() {
            return port;
        }
    }

    public ServerInfo discover() {
        String BROADCAST_ADDRESS = "255.255.255.255";
        int BROADCAST_PORT = 41234;

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);

            // Wait maximum 3 seconds
            socket.setSoTimeout(3000);

            InetAddress broadcastAddress = InetAddress.getByName(BROADCAST_ADDRESS);

            byte[] message = "Who's server?".getBytes(StandardCharsets.UTF_8);

            int requestId = 1;

            Packet request = new Packet(OpCode.DISCOVER_REQUEST, requestId, message);

            // Send discovery request
            PacketCodec.sendUDP(socket, broadcastAddress, BROADCAST_PORT, request);

            System.out.println("Discover request sent!");

            byte[] buffer = new byte[4096];

            DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);

            while (true) {
                // Receive response
                socket.receive(udpPacket);

                // Extract actual received bytes
                byte[] receivedData = Arrays.copyOfRange(
                        udpPacket.getData(),
                        udpPacket.getOffset(),
                        udpPacket.getOffset() + udpPacket.getLength());

                // Deserialize
                Packet response = PacketCodec.fromBytes(receivedData);

                // Validate response
                if (response.op() != OpCode.DISCOVER_RESPONSE) {
                    continue;
                }

                if (response.requestId() != requestId) {
                    continue;
                }

                // Extract server information
                String hostAddress = udpPacket.getAddress().getHostAddress();

                int serverPort = Integer.parseInt(new String(response.data(), StandardCharsets.UTF_8));

                System.out.printf("Server found: %s:%d%n", hostAddress, serverPort);

                return new ServerInfo(hostAddress, serverPort);
            }

        } catch (SocketTimeoutException e) {
            System.out.println("No server found!");
            return null;

        } catch (Exception e) {
            System.err.println("Discovery error: " + e.getMessage());
            return null;
        }
    }
}
