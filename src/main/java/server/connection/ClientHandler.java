package server.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Optional;
import java.util.stream.Collectors;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import protocols.PacketValidator;
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
    private OutputStream output;

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
            this.output = socket.getOutputStream();

            if (!handshake(input)) {
                return;
            }

            if (!handleAuth(input)) {
                return;
            }

            socket.setSoTimeout(0);
            readPackets(input);
        } catch (IOException e) {
            System.err.println("Client " + address + " error: " + e.getMessage());
        } finally {
            if (session != null) {
                sessions.remove(session);
            }
            System.out.println("Client " + address + " disconnected");
        }
    }

    public synchronized void sendPacket(Packet packet) throws IOException {
        if (output == null) {
            throw new IOException("Client output is not available");
        }
        PacketCodec.write(output, packet);
    }

    private boolean handshake(InputStream input) throws IOException {
        Packet hello = PacketCodec.read(input);

        if (hello == null || hello.op() != OpCode.HELLO || hello.payloadLength() != 0) {
            System.err.println("Invalid or missing HELLO from " + socket.getRemoteSocketAddress());
            return false;
        }

        Packet response = new Packet(OpCode.HELLO_ACK, hello.requestId(), new byte[0]);
        sendPacket(response);

        System.out.println("Handshake successful with " + socket.getRemoteSocketAddress());
        return true;
    }

    private void readPackets(InputStream input) throws IOException {
        Packet packet;
        while ((packet = PacketCodec.read(input)) != null) {
            switch (packet.op()) {
                case CHAT_MESSAGE -> {
                    if (packet.payloadLength() > MAX_CHAT_BYTES) {
                        throw new IOException("Message exceeds limit");
                    }
                    String message = packet.text();

                    System.out.println(session.username() + ": " + message);
                }

                case USER_LIST_REQUEST -> handleUserList(packet);

                case PEER_LOOKUP_REQUEST -> handlePeerLookup(packet);

                default -> throw new IOException("Unexpected packet: " + packet.op());
            }
        }
    }

    private boolean handleAuth(InputStream input) throws IOException {
        Packet request = PacketCodec.read(input);

        try {
            PacketValidator.validateRequest(request, OpCode.AUTH_REQUEST, 0, MAX_AUTH_BYTES);
        } catch (IOException e) {
            return false;
        }

        String credentials = request.text();
        String[] parts = credentials.split("\n", -1);

        String status;
        int peerPort = 0;

        if (parts.length == 3) {
            try {
                peerPort = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {}
        }

        if (parts.length != 3
            || parts[0].isBlank()
            || parts[1].isEmpty()
            || peerPort < 1
            || peerPort > 65535) {
            status = "INVALID_AUTH_REQUEST";
        } else if (!authService.authenticate(parts[0], parts[1])) {
            status = "INVALID_CREDENTIALS";
        } else {
            UserSession candidate = new UserSession(
                    parts[0],
                    socket.getInetAddress().getHostAddress(),
                    peerPort
            );

            if (sessions.register(candidate)) {
                session = candidate;
                status = "OK";
            } else {
                status = "ALREADY_LOGGED_IN";
            }
        }

        Packet response = Packet.text(OpCode.AUTH_RESPONSE, request.requestId(), status);
        sendPacket(response);

        return status.equals("OK");
    }

    private void handleUserList(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.USER_LIST_REQUEST, 0, 0);

        String usernames = sessions.listOnlineUsers().stream()
                .map(UserSession::username)
                .sorted()
                .collect(Collectors.joining("\n"));

        Packet response = Packet.text(OpCode.USER_LIST_RESPONSE, request.requestId(), usernames);

        sendPacket(response);
    }

    private void handlePeerLookup(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.PEER_LOOKUP_REQUEST, 1, PacketCodec.MAX_PAYLOAD_LENGTH);

        String targetUsername = request.text();

        Optional<UserSession> target = sessions.find(targetUsername);
        String payload;
        if (target.isEmpty()) {
            payload = "USER_OFFLINE";
        }
        else {
            UserSession targetUser = target.get();
            payload = "OK\n" + targetUser.ip() + "\n" + targetUser.port();
        }

        Packet response = Packet.text(OpCode.PEER_LOOKUP_RESPONSE, request.requestId(), payload);
        sendPacket(response);
    }
}
