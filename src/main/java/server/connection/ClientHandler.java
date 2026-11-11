package server.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import protocols.PacketValidator;
import server.auth.AuthService;
import server.session.ChatSession;
import server.session.ChatSessionRegistry;
import server.session.UserSessionRegistry;
import server.session.UserSession;

public class ClientHandler implements Runnable {
    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private static final int MAX_AUTH_BYTES = 4096;

    private final Socket socket;
    private final UserSessionRegistry userSessions;
    private final ChatSessionRegistry chatSessions;
    private final AuthService authService;

    private UserSession session;
    private OutputStream output;

    public ClientHandler(Socket socket, UserSessionRegistry userSessions,
            ChatSessionRegistry chatSessions, AuthService authService) {
        this.socket = socket;
        this.userSessions = userSessions;
        this.chatSessions = chatSessions;
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
                synchronized (userSessions) {
                    userSessions.remove(session);
                    chatSessions.userDisconnected(session);
                }
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

                case CHAT_MESSAGE_BROADCAST -> handleBroadcastMessage(packet);

                case USER_LIST_REQUEST -> handleUserList(packet);

                case PEER_LOOKUP_REQUEST -> handlePeerLookup(packet);

                case CHAT_SESSION_REPORT -> handleChatSessionReport(packet);

                case CHAT_SESSION_LIST_REQUEST -> handleChatSessionList(packet);

                case LOGOUT_REQUEST -> {
                    PacketValidator.validateRequest(packet, OpCode.LOGOUT_REQUEST, 0, 0);
                    sendPacket(Packet.text(OpCode.LOGOUT_RESPONSE, packet.requestId(), ""));
                    return;
                }

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
            } catch (NumberFormatException e) {
            }
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
                    peerPort,
                    socket);

            if (userSessions.register(candidate)) {
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

        String usernames = userSessions.listOnlineUsers().stream()
                .map(UserSession::username)
                .sorted()
                .collect(Collectors.joining("\n"));

        Packet response = Packet.text(OpCode.USER_LIST_RESPONSE, request.requestId(), usernames);

        sendPacket(response);
    }

    private void handlePeerLookup(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.PEER_LOOKUP_REQUEST, 1, PacketCodec.MAX_PAYLOAD_LENGTH);

        String data = request.text();

        String[] parts = data.split("\n");

        String targetUsername = parts[0];
        String username = parts[1];

        Optional<UserSession> target = userSessions.find(targetUsername);
        String payload;
        if (target.isEmpty()) {
            payload = "USER_OFFLINE";
        } else if (chatSessions.isConnected(username, targetUsername)) {
            payload = "ALREADY_CONNECTED";
        } else {
            UserSession targetUser = target.get();
            payload = "OK\n" + targetUser.ip() + "\n" + targetUser.port();
        }

        Packet response = Packet.text(OpCode.PEER_LOOKUP_RESPONSE, request.requestId(), payload);
        sendPacket(response);
    }

    private void handleChatSessionReport(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.CHAT_SESSION_REPORT, 1, MAX_AUTH_BYTES);
        String[] parts = request.text().split("\n", -1);
        String status = "INVALID_SESSION_REPORT";
        if (parts.length == 3 && !parts[1].isBlank()
                && !parts[1].equals(session.username())) {
            try {
                UUID id = UUID.fromString(parts[0]);
                if (id.toString().equals(parts[0])) {
                    synchronized (userSessions) {
                        status = applyChatSessionReport(id, parts[1], parts[2]);
                    }
                }
            } catch (IllegalArgumentException e) {
                // Malformed metadata is rejected without closing the management connection.
            }
        }
        sendPacket(Packet.text(OpCode.CHAT_SESSION_REPORT_RESPONSE, request.requestId(), status));
    }

    private String applyChatSessionReport(UUID id, String peerUsername, String state) {
        if (state.equals("CONNECTED")) {
            Optional<UserSession> peer = userSessions.find(peerUsername);
            if (peer.isEmpty())
                return "USER_OFFLINE";
            return chatSessions.connected(id, session, peer.get()) ? "OK" : "INVALID_SESSION";
        }
        if (state.equals("DISCONNECTED")) {
            return chatSessions.disconnected(id, session, peerUsername) ? "OK" : "INVALID_SESSION";
        }
        return "INVALID_SESSION_REPORT";
    }

    private void handleChatSessionList(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.CHAT_SESSION_LIST_REQUEST, 0, 0);
        String payload = chatSessions.listSessions().stream()
                .sorted(java.util.Comparator.comparing(ChatSession::sessionId))
                .map(chat -> chat.sessionId() + "\t" + chat.userA().username() + "\t"
                        + chat.userB().username() + "\t" + chat.state())
                .collect(Collectors.joining("\n"));
        sendPacket(Packet.text(OpCode.CHAT_SESSION_LIST_RESPONSE, request.requestId(), payload));
    }

    // broad cast

    private void handleBroadcastMessage(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.CHAT_MESSAGE_BROADCAST, 0, MAX_CHAT_BYTES);
        List<UserSession> listOfUser = userSessions.listOnlineUsers();

        for (UserSession u : listOfUser) {
            OutputStream userOutputStream = u.clientSocket().getOutputStream();
            if (userOutputStream == null) {
                throw new IOException("Client output is not available");
            }
            PacketCodec.write(userOutputStream, request);
        }
    }
}
