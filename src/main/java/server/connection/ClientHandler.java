package server.connection;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import protocols.Json;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import network.PacketChannel;
import network.HeartbeatMonitor;
import protocols.PacketValidator;
import server.auth.AuthService;
import server.broadcast.BroadcastFileRegistry;
import server.community.CommunityService;
import server.community.CommunityRequestHandler;
import server.broadcast.BroadcastService;
import server.session.ChatSession;
import server.session.ChatSessionRegistry;
import server.session.UserSessionRegistry;
import server.session.UserSession;
import server.storage.FileStorageService;
import server.security.ServerIdentity;
import security.SessionKeys;
import security.X25519;
import security.PeerKeyProof;

public class ClientHandler implements Runnable {
    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private static final int MAX_AUTH_BYTES = 4096;

    private final Socket socket;
    private final UserSessionRegistry userSessions;
    private final ChatSessionRegistry chatSessions;
    private final AuthService authService;
    private final ServerIdentity identity;
    private final CommunityRequestHandler communityRequests;
    private final BroadcastService broadcasts;

    private UserSession session;
    private PacketChannel channel;
    private HeartbeatMonitor heartbeat;

    public ClientHandler(Socket socket, UserSessionRegistry userSessions,
            ChatSessionRegistry chatSessions, AuthService authService, ServerIdentity identity) {
        this(socket, userSessions, chatSessions, authService, null, null, null, identity);
    }

    public ClientHandler(Socket socket, UserSessionRegistry userSessions,
            ChatSessionRegistry chatSessions, AuthService authService,
            CommunityService communityService, BroadcastFileRegistry broadcastFileRegistry,
            FileStorageService fileStorageService, ServerIdentity identity) {
        this.socket = socket;
        this.userSessions = userSessions;
        this.chatSessions = chatSessions;
        this.authService = authService;
        this.identity = Objects.requireNonNull(identity, "identity");
        communityRequests = communityService == null ? null : new CommunityRequestHandler(communityService);
        broadcasts = communityService == null || broadcastFileRegistry == null || fileStorageService == null ? null
                : new BroadcastService(userSessions, communityService, broadcastFileRegistry, fileStorageService);
    }

    @Override
    public void run() {
        String address = socket.getRemoteSocketAddress().toString();

        try (socket) {
            socket.setSoTimeout(10000);

            channel = new PacketChannel(socket);

            Packet first = channel.read();
            if (first == null) return;

            if (first.op() != OpCode.HELLO) {
                System.err.println("Invalid or missing HELLO from " + address);
                return;
            }

            if (first.integer("protocolVersion") != Packet.PROTOCOL_VERSION) {
                channel.send(Packet.status(OpCode.HELLO_ACK, first.sequence(), "UNSUPPORTED_PROTOCOL_VERSION"));
                return;
            }

            KeyPair serverKeyPair = createKeyPair();
            String encodedPublicKey = Base64.getEncoder().encodeToString(serverKeyPair.getPublic().getEncoded());
            byte[] signedData = Json.bytes(Json.object("purpose", "server-handshake",
                    "hello", first.jsonBody(), "serverPublicKey", encodedPublicKey));
            String serverSignature = createServerSignature(first, signedData);
            SessionKeys sessionKeys = createSessionKeys(serverKeyPair, first, signedData);
            Packet response = Packet.reply(OpCode.HELLO_ACK, first.sequence(),
                    Json.object("status", "OK", "protocolVersion", Packet.PROTOCOL_VERSION,
                            "serverSignature", serverSignature, "publicKey", encodedPublicKey));
            channel.send(response);

            channel.confirmEncryption(sessionKeys, PacketChannel.Role.RESPONDER);
            System.out.println("Handshake successful with " + address);

            Packet request = channel.read();
            if (request == null) return;
            if (request.op() == OpCode.FILE_BROADCAST_UPLOAD_START) {
                requireBroadcasts().handleBroadcastUploadConnection(request, channel);
                return;
            }
            if (request.op() == OpCode.FILE_BROADCAST_DOWNLOAD_START) {
                requireBroadcasts().handleBroadcastDownloadConnection(request, channel);
                return;
            }

            if (!handleAuth(request)) {
                return;
            }

            socket.setSoTimeout(0);
            heartbeat = new HeartbeatMonitor(channel, () -> {
                try { socket.close(); } catch (IOException e) {
                    System.err.println("Cannot close expired client: " + e.getMessage());
                }
            });
            heartbeat.start();
            readPackets();
        } catch (IOException e) {
            System.err.println("Client " + address + " error: " + e.getMessage());
        } finally {
            if (heartbeat != null) heartbeat.close();
            if (session != null) {
                synchronized (userSessions) {
                    userSessions.remove(session);
                    chatSessions.userDisconnected(session);
                }
            }
            System.out.println("Client " + address + " disconnected");
        }
    }

    private void readPackets() throws IOException {
        Packet packet;
        while ((packet = channel.read()) != null) {
            if (heartbeat.handle(packet)) continue;
            switch (packet.op()) {
                case CHAT_MESSAGE -> {
                    if (packet.string("content").getBytes(StandardCharsets.UTF_8).length > MAX_CHAT_BYTES) {
                        throw new IOException("Message exceeds limit");
                    }
                    String message = packet.string("content");
                    System.out.println(session.username() + ": " + message);
                }

                case CHAT_MESSAGE_BROADCAST -> requireBroadcasts().handleBroadcastMessage(packet, session);

                case USER_LIST_REQUEST -> handleUserList(packet);

                case PEER_LOOKUP_REQUEST -> handlePeerLookup(packet);

                case PEER_KEY_REQUEST -> handlePeerKey(packet);

                case CHAT_SESSION_REPORT -> handleChatSessionReport(packet);

                case CHAT_SESSION_LIST_REQUEST -> handleChatSessionList(packet);

                // Community operations
                case COMMUNITY_LIST_REQUEST, COMMUNITY_CREATE_REQUEST, COMMUNITY_JOIN_REQUEST,
                     COMMUNITY_LEAVE_REQUEST, COMMUNITY_MEMBERS_REQUEST, COMMUNITY_SELECT_REQUEST,
                     COMMUNITY_INFO_REQUEST -> requireCommunityRequests().handle(packet, session);

                // Broadcast file operations
                case FILE_BROADCAST_UPLOAD_REQUEST -> requireBroadcasts().handleBroadcastFileUploadRequest(packet, session);
                case FILE_BROADCAST_DECISION -> requireBroadcasts().handleBroadcastFileDecision(packet, session);
                case FILE_BROADCAST_DOWNLOAD_REQUEST -> requireBroadcasts().handleBroadcastFileDownloadRequest(packet, session);

                case LOGOUT_REQUEST -> {
                    PacketValidator.validateRequest(packet, OpCode.LOGOUT_REQUEST, 0, 0);
                    channel.send(Packet.status(OpCode.LOGOUT_RESPONSE, packet.sequence(), "OK"));
                    return;
                }

                default -> throw new IOException("Unexpected packet: " + packet.op());
            }
        }
    }

    private String createServerSignature(Packet hello, byte[] signedData) throws IOException {
        PacketValidator.validateRequest(hello, OpCode.HELLO, 1, 4096);
        try {
            byte[] serverVerificationChallenge = Base64.getDecoder()
                    .decode(hello.string("serverVerificationChallenge"));
            if (serverVerificationChallenge.length != 32) {
                throw new IOException("Server verification challenge must be 32 bytes");
            }
            byte[] signature = identity.sign(signedData);
            return Base64.getEncoder().encodeToString(signature);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IOException("Cannot sign server handshake", e);
        }
    }

    private SessionKeys createSessionKeys(KeyPair serverKeyPair, Packet hello, byte[] signedData) throws IOException {
        try {
            PublicKey clientPublicKey = X25519.decodePublicKey(hello.string("publicKey"));
            return X25519.deriveSessionKeys(serverKeyPair, clientPublicKey, signedData);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IOException("Cannot create session keys", e);
        }
    }

    private KeyPair createKeyPair() throws IOException {
        try {
            return X25519.createKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot create server X25519 key pair", e);
        }
    }

    private boolean handleAuth(Packet request) throws IOException {
        try {
            PacketValidator.validateRequest(request, OpCode.AUTH_REQUEST, 0, MAX_AUTH_BYTES);
        } catch (IOException e) {
            return false;
        }

        String status;
        String username = "";
        String password = "";
        int peerPort = 0;
        try {
            username = request.string("username");
            password = request.string("password");
            peerPort = request.integer("peerPort");
        } catch (IOException e) {
            // A well-framed but malformed auth request receives a structured failure.
        }
        if (username.isBlank() || password.isEmpty() || peerPort < 1 || peerPort > 65535) {
            status = "INVALID_AUTH_REQUEST";
        } else if (!authService.authenticate(username, password)) {
            status = "INVALID_CREDENTIALS";
        } else {
            UserSession candidate = new UserSession(username,
                    socket.getInetAddress().getHostAddress(), peerPort, channel);
            if (userSessions.register(candidate)) {
                session = candidate;
                status = "OK";
            } else {
                status = "ALREADY_LOGGED_IN";
            }
        }

        Packet response = Packet.status(OpCode.AUTH_RESPONSE, request.sequence(), status);
        channel.send(response);

        return status.equals("OK");
    }

    private void handleUserList(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.USER_LIST_REQUEST, 0, 0);

        var usernames = userSessions.listOnlineUsers().stream()
                .map(UserSession::username).sorted().toList();
        Packet response = Packet.reply(OpCode.USER_LIST_RESPONSE, request.sequence(),
                Json.object("status", "OK", "users", usernames));

        channel.send(response);
    }

    private void handlePeerKey(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.PEER_KEY_REQUEST, 1, 4096);
        String sessionId;
        String publicKey;
        try {
            sessionId = request.string("sessionId");
            if (!UUID.fromString(sessionId).toString().equals(sessionId)) {
                throw new IllegalArgumentException("Invalid peer session ID");
            }
            PublicKey key = X25519.decodePublicKey(request.string("publicKey"));
            publicKey = Base64.getEncoder().encodeToString(key.getEncoded());
        } catch (IOException | IllegalArgumentException | GeneralSecurityException e) {
            channel.send(Packet.status(OpCode.PEER_KEY_RESPONSE, request.sequence(), "INVALID_PEER_KEY"));
            return;
        }

        byte[] signature;
        try {
            signature = identity.sign(PeerKeyProof.create(session.username(), sessionId, publicKey));
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot sign peer public key", e);
        }
        channel.send(Packet.reply(OpCode.PEER_KEY_RESPONSE, request.sequence(),
                Json.object("status", "OK", "username", session.username(),
                        "sessionId", sessionId, "publicKey", publicKey,
                        "serverSignature", Base64.getEncoder().encodeToString(signature))));
    }

    private void handlePeerLookup(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.PEER_LOOKUP_REQUEST, 1, PacketCodec.MAX_JSON_LENGTH);

        String targetUsername = request.string("username");
        Optional<UserSession> target = userSessions.find(targetUsername);
        JsonObject payload;
        if (target.isEmpty()) {
            payload = Json.object("status", "USER_OFFLINE");
        } else {
            // Reports are advisory: stale CONNECTED metadata must not block reconnection.
            UserSession targetUser = target.get();
            payload = Json.object("status", "OK", "ip", targetUser.ip(), "peerPort", targetUser.port());
        }
        Packet response = Packet.reply(OpCode.PEER_LOOKUP_RESPONSE, request.sequence(), payload);
        channel.send(response);
    }

    private void handleChatSessionReport(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.CHAT_SESSION_REPORT, 1, MAX_AUTH_BYTES);
        String status = "INVALID_SESSION_REPORT";
        try {
            String idText = request.string("sessionId");
            String peerUsername = request.string("peerUsername");
            String state = request.string("state");
            UUID id = UUID.fromString(idText);
            if (id.toString().equals(idText) && !peerUsername.isBlank()
                    && !peerUsername.equals(session.username())) {
                synchronized (userSessions) {
                    status = applyChatSessionReport(id, peerUsername, state);
                }
            }
        } catch (IllegalArgumentException | IOException e) {
            // Invalid session metadata does not close the management connection.
        }
        channel.send(Packet.status(OpCode.CHAT_SESSION_REPORT_RESPONSE, request.sequence(), status));
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
        JsonArray sessions = new JsonArray();
        chatSessions.listSessions().stream()
                .sorted(java.util.Comparator.comparing(ChatSession::sessionId))
                .forEach(chat -> sessions.add(Json.object("sessionId", chat.sessionId().toString(),
                        "userA", chat.userA().username(), "userB", chat.userB().username(),
                        "state", chat.state().name())));
        channel.send(Packet.reply(OpCode.CHAT_SESSION_LIST_RESPONSE, request.sequence(),
                Json.object("status", "OK", "sessions", sessions)));
    }

    private CommunityRequestHandler requireCommunityRequests() throws IOException {
        if (communityRequests == null) throw new IOException("Community service not available");
        return communityRequests;
    }

    private BroadcastService requireBroadcasts() throws IOException {
        if (broadcasts == null) throw new IOException("Broadcast file service not available");
        return broadcasts;
    }
}
