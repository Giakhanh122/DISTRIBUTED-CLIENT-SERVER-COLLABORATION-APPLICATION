package server.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import protocols.FileTransferMeta;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import server.auth.AuthService;
import server.session.SessionRegistry;
import server.session.UserSession;

public class ClientHandler implements Runnable {
    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private static final int MAX_AUTH_BYTES = 4096;
    private static final ConcurrentHashMap<String, ClientHandler> HANDLERS = new ConcurrentHashMap<>();

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
                HANDLERS.remove(session.username());
            }

            System.out.println("Client " + address + " disconnected");
        }
    }

    public synchronized void sendPacket(Packet packet) throws IOException {
        if (output != null) {
            PacketCodec.write(output, packet);
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

        if (parts.length < 2 || parts[0].isBlank() || parts[1].isEmpty()) {
            status = "INVALID_AUTH_REQUEST";
        } else if (!authService.authenticate(parts[0], parts[1])) {
            status = "INVALID_CREDENTIALS";
        } else {
            int peerPort = 0;
            if (parts.length >= 3) {
                try {
                    peerPort = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException ignored) {}
            }

            UserSession candidate = new UserSession(
                    parts[0],
                    socket.getInetAddress().getHostAddress(),
                    peerPort
            );

            if (sessions.register(candidate)) {
                session = candidate;
                HANDLERS.put(candidate.username(), this);
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

    private void handlePeerLookup(Packet request, OutputStream output) throws IOException {
        if (request.requestId() <= 0 || request.payloadLength() == 0) {
            throw new IOException("Invalid PEER_LOOKUP_REQUEST");
        }

        String targetUsername = new String(request.data(), StandardCharsets.UTF_8);
        if (targetUsername.isEmpty()) {
            throw new IOException("Target username must not be empty");
        }

        Optional<UserSession> target = sessions.find(targetUsername);
        String payload;
        if (target.isEmpty()) {
            payload = "USER_OFFLINE";
        } else {
            UserSession targetUser = target.get();
            payload = "OK\n" + targetUser.ip() + "\n" + targetUser.port();
        }

        Packet response = new Packet(OpCode.PEER_LOOKUP_RESPONSE, request.requestId(), payload.getBytes(StandardCharsets.UTF_8));
        PacketCodec.write(output, response);
    }

    private void handleFileOffer(Packet packet) throws IOException {
        FileTransferMeta meta = FileTransferMeta.fromBytes(packet.data());
        ClientHandler receiver = HANDLERS.get(meta.receiver());
        if (receiver != null) {
            receiver.sendPacket(new Packet(OpCode.FILE_OFFER, packet.requestId(), packet.data()));
        } else {
            String reject = meta.transferId() + "\n" + meta.sender() + "\nREJECT\nUser offline";
            sendPacket(new Packet(OpCode.FILE_DECISION, packet.requestId(), reject.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private void handleFileDecision(Packet packet) throws IOException {
        String payload = new String(packet.data(), StandardCharsets.UTF_8);
        String[] parts = payload.split("\n", -1);
        if (parts.length >= 3) {
            ClientHandler sender = HANDLERS.get(parts[1]);
            if (sender != null) {
                String forwarded = parts[2].equalsIgnoreCase("ACCEPT")
                        ? parts[0] + "\n" + parts[1] + "\nACCEPT\n" + socket.getInetAddress().getHostAddress() + "\n" + (parts.length > 3 ? parts[3] : "0")
                        : payload;
                sender.sendPacket(new Packet(OpCode.FILE_DECISION, packet.requestId(), forwarded.getBytes(StandardCharsets.UTF_8)));
            }
        }
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
                case PEER_LOOKUP_REQUEST -> handlePeerLookup(packet, output);
                case FILE_OFFER -> handleFileOffer(packet);
                case FILE_DECISION -> handleFileDecision(packet);
                default -> throw new IOException("Unexpected packet: " + packet.op());
            }
        }
    }
}
