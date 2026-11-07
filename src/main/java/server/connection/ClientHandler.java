package server.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import server.auth.AuthService;
import server.session.SessionRegistry;
import server.session.UserSession;

public class ClientHandler implements Runnable {
    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private static final int MAX_AUTH_BYTES = 4096;

    private final Socket socket;
    private final SessionRegistry sessions;
    private final AuthService authService;

    private UserSession session;

    public ClientHandler(Socket socket, SessionRegistry sessions, AuthService authService) {
        this.socket = socket;
        this.sessions = sessions;
        this.authService = authService;
    }

    @Override
    public void run() {
        String address = socket.getRemoteSocketAddress().toString();

        try (socket) {
            socket.setSoTimeout(10000);

            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            if (!handshake(input, output)) {
                return;
            }

            socket.setSoTimeout(0);

            if (!handleAuth(input, output)) {
                return;
            }

            readPackets(input, output);
        } catch (IOException e) {
            System.err.println("Client " + address + " error: " + e.getMessage());
        } finally {
            if (session != null) {
                sessions.remove(session);
            }

            System.out.println("Client " + address + " disconnected");
        }
    }

    private boolean handshake(InputStream input, OutputStream output) throws IOException {
        Packet hello = PacketCodec.read(input);

        if (hello == null || hello.op() != OpCode.HELLO || hello.payloadLength() != 0) {
            System.err.println("Invalid or missing HELLO from " + socket.getRemoteSocketAddress());
            return false;
        }

        Packet response = new Packet(OpCode.HELLO_ACK, hello.requestId(), new byte[0]);

        PacketCodec.write(output, response);

        System.out.println("Handshake successful with " + socket.getRemoteSocketAddress());
        return true;
    }

    private boolean handleAuth(InputStream input, OutputStream output) throws IOException {
        Packet request = PacketCodec.read(input);

        if (request == null
                || request.op() != OpCode.AUTH_REQUEST
                || request.requestId() <= 0
                || request.payloadLength() > MAX_AUTH_BYTES) {
            return false;
        }

        String credentials = new String(request.data(), StandardCharsets.UTF_8);
        String[] parts = credentials.split("\n", -1);

        String status;

        if (parts.length != 2 || parts[0].isBlank() || parts[1].isEmpty()) {
            status = "INVALID_AUTH_REQUEST";
        } else if (!authService.authenticate(parts[0], parts[1])) {
            status = "INVALID_CREDENTIALS";
        } else {
            UserSession candidate = new UserSession(
                    parts[0],
                    socket.getInetAddress().getHostAddress(),
                    0 // Chưa triển khai cổng lắng nghe peer.
            );

            if (sessions.register(candidate)) {
                session = candidate;
                status = "OK";
            } else {
                status = "ALREADY_LOGGED_IN";
            }
        }

        Packet response = new Packet(
                OpCode.AUTH_RESPONSE, request.requestId(), status.getBytes(StandardCharsets.UTF_8));

        PacketCodec.write(output, response);

        return status.equals("OK");
    }

    private void handleUserList(Packet request, OutputStream output) throws IOException {
        if (request.requestId() <= 0 || request.payloadLength() != 0) {
            throw new IOException("Invalid USER_LIST_REQUEST");
        }

        String usernames = sessions.listOnlineUsers().stream()
                .map(UserSession::username)
                .sorted()
                .collect(Collectors.joining("\n"));

        Packet response = new Packet(
                OpCode.USER_LIST_RESPONSE,
                request.requestId(),
                usernames.getBytes(StandardCharsets.UTF_8));

        PacketCodec.write(output, response);
    }

    private void readPackets(InputStream input, OutputStream output) throws IOException {
        Packet packet;
        while ((packet = PacketCodec.read(input)) != null) {
            switch (packet.op()) {
                case CHAT_MESSAGE -> {
                    if (packet.payloadLength() > MAX_CHAT_BYTES) {
                        throw new IOException("Message exceeds limit");
                    }

                    String message = new String(packet.data(), StandardCharsets.UTF_8);

                    System.out.println(session.username() + ": " + message);
                }

                case USER_LIST_REQUEST -> handleUserList(packet, output);

                default -> throw new IOException("Unexpected packet: " + packet.op());
            }
        }
    }
}
