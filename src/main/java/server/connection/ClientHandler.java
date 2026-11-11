package server.connection;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import protocols.Json;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import protocols.PacketChannel;
import protocols.PacketValidator;
import server.auth.AuthService;
import server.broadcast.BroadcastFileMeta;
import server.broadcast.BroadcastFileRegistry;
import server.community.Community;
import server.community.CommunityService;
import server.session.ChatSession;
import server.session.ChatSessionRegistry;
import server.session.UserSessionRegistry;
import server.session.UserSession;
import server.storage.FileStorageService;
import transfer.FileSender;
import transfer.FileDataReader;

public class ClientHandler implements Runnable {
    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private static final int MAX_AUTH_BYTES = 4096;
    private static final int MAX_COMMUNITY_BYTES = 4096;
    private static final long MAX_BROADCAST_FILE_SIZE = 100L * 1024 * 1024;


    private final Socket socket;
    private final UserSessionRegistry userSessions;
    private final ChatSessionRegistry chatSessions;
    private final AuthService authService;
    private final CommunityService communityService;
    private final BroadcastFileRegistry broadcastFileRegistry;
    private final FileStorageService fileStorageService;

    private UserSession session;
    private PacketChannel channel;

    public ClientHandler(Socket socket, UserSessionRegistry userSessions,
            ChatSessionRegistry chatSessions, AuthService authService) {
        this(socket, userSessions, chatSessions, authService, null, null, null);
    }

    public ClientHandler(Socket socket, UserSessionRegistry userSessions,
            ChatSessionRegistry chatSessions, AuthService authService,
            CommunityService communityService, BroadcastFileRegistry broadcastFileRegistry,
            FileStorageService fileStorageService) {
        this.socket = socket;
        this.userSessions = userSessions;
        this.chatSessions = chatSessions;
        this.authService = authService;
        this.communityService = communityService;
        this.broadcastFileRegistry = broadcastFileRegistry;
        this.fileStorageService = fileStorageService;
    }

    @Override
    public void run() {
        String address = socket.getRemoteSocketAddress().toString();

        try (socket) {
            socket.setSoTimeout(10000);

            channel = new PacketChannel(socket);

            Packet first = channel.read();
            if (first == null) return;

            // Demultiplex: HELLO = normal client, upload/download start = file data connection
            if (first.op() == OpCode.FILE_BROADCAST_UPLOAD_START) {
                handleBroadcastUploadConnection(first);
                return;
            }
            if (first.op() == OpCode.FILE_BROADCAST_DOWNLOAD_START) {
                handleBroadcastDownloadConnection(first);
                return;
            }

            // Normal client session
            if (first.op() != OpCode.HELLO) {
                System.err.println("Invalid or missing HELLO from " + address);
                return;
            }

            if (first.integer("protocolVersion") != Packet.PROTOCOL_VERSION) {
                channel.send(Packet.status(OpCode.HELLO_ACK, first.sequence(), "UNSUPPORTED_PROTOCOL_VERSION"));
                return;
            }

            Packet response = Packet.reply(OpCode.HELLO_ACK, first.sequence(), Json.object("status", "OK", "protocolVersion", Packet.PROTOCOL_VERSION));
            channel.send(response);
            System.out.println("Handshake successful with " + address);

            if (!handleAuth()) {
                return;
            }

            socket.setSoTimeout(0);
            readPackets();
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

    private void readPackets() throws IOException {
        Packet packet;
        while ((packet = channel.read()) != null) {
            switch (packet.op()) {
                case CHAT_MESSAGE -> {
                    if (packet.string("content").getBytes(StandardCharsets.UTF_8).length > MAX_CHAT_BYTES) {
                        throw new IOException("Message exceeds limit");
                    }
                    String message = packet.string("content");
                    System.out.println(session.username() + ": " + message);
                }

                case CHAT_MESSAGE_BROADCAST -> handleBroadcastMessage(packet);

                case USER_LIST_REQUEST -> handleUserList(packet);

                case PEER_LOOKUP_REQUEST -> handlePeerLookup(packet);

                case CHAT_SESSION_REPORT -> handleChatSessionReport(packet);

                case CHAT_SESSION_LIST_REQUEST -> handleChatSessionList(packet);

                // Community operations
                case COMMUNITY_LIST_REQUEST -> handleCommunityList(packet);
                case COMMUNITY_CREATE_REQUEST -> handleCommunityCreate(packet);
                case COMMUNITY_JOIN_REQUEST -> handleCommunityJoin(packet);
                case COMMUNITY_LEAVE_REQUEST -> handleCommunityLeave(packet);
                case COMMUNITY_MEMBERS_REQUEST -> handleCommunityMembers(packet);
                case COMMUNITY_SELECT_REQUEST -> handleCommunitySelect(packet);
                case COMMUNITY_INFO_REQUEST -> handleCommunityInfo(packet);

                // Broadcast file operations
                case FILE_BROADCAST_UPLOAD_REQUEST -> handleBroadcastFileUploadRequest(packet);
                case FILE_BROADCAST_DECISION -> handleBroadcastFileDecision(packet);
                case FILE_BROADCAST_DOWNLOAD_REQUEST -> handleBroadcastFileDownloadRequest(packet);

                case LOGOUT_REQUEST -> {
                    PacketValidator.validateRequest(packet, OpCode.LOGOUT_REQUEST, 0, 0);
                    channel.send(Packet.status(OpCode.LOGOUT_RESPONSE, packet.sequence(), "OK"));
                    return;
                }

                default -> throw new IOException("Unexpected packet: " + packet.op());
            }
        }
    }

    private boolean handleAuth() throws IOException {
        Packet request = channel.read();

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

    private void handlePeerLookup(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.PEER_LOOKUP_REQUEST, 1, PacketCodec.MAX_JSON_LENGTH);

        String targetUsername = request.string("username");
        Optional<UserSession> target = userSessions.find(targetUsername);
        JsonObject payload;
        if (target.isEmpty()) {
            payload = Json.object("status", "USER_OFFLINE");
        } else if (chatSessions.isConnected(session.username(), targetUsername)) {
            payload = Json.object("status", "ALREADY_CONNECTED");
        } else {
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

    // ======================== Community Handlers ========================

    private void handleCommunityList(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_LIST_REQUEST, 0, 0);
        JsonObject result = communityService.listCommunities(session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_LIST_RESPONSE, request.sequence(), result));
    }

    private void handleCommunityCreate(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_CREATE_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String name = request.string("name");
        JsonObject result = communityService.createCommunity(name, session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_CREATE_RESPONSE, request.sequence(), result));
    }

    private void handleCommunityJoin(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_JOIN_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.string("communityId");
        JsonObject result = communityService.joinCommunity(communityId, session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_JOIN_RESPONSE, request.sequence(), result));
    }

    private void handleCommunityLeave(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_LEAVE_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.string("communityId");
        JsonObject result = communityService.leaveCommunity(communityId, session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_LEAVE_RESPONSE, request.sequence(), result));
    }

    private void handleCommunityMembers(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_MEMBERS_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.string("communityId");
        JsonObject result = communityService.listMembers(communityId, session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_MEMBERS_RESPONSE, request.sequence(), result));
    }

    private void handleCommunitySelect(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_SELECT_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.string("communityId");
        JsonObject result = communityService.selectCommunity(communityId, session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_SELECT_RESPONSE, request.sequence(), result));
    }

    private void handleCommunityInfo(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_INFO_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.string("communityId");
        JsonObject result = communityService.communityInfo(communityId, session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_INFO_RESPONSE, request.sequence(), result));
    }

    // ======================== Broadcast Message (community-scoped) ========================

    private void handleBroadcastMessage(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.CHAT_MESSAGE_BROADCAST, 1, PacketCodec.MAX_JSON_LENGTH);
        String communityId = request.string("communityId");
        String message = request.string("content");
        if (message.getBytes(StandardCharsets.UTF_8).length > MAX_CHAT_BYTES) {
            throw new IOException("Message exceeds limit");
        }

        // Validate membership
        if (communityService == null) {
            throw new IOException("Community service not available");
        }
        Community community = communityService.validateMembership(communityId, session.username());
        if (community == null) {
            throw new IOException("Not a member of the target community");
        }

        // Include sender and community context in the broadcast event.
        Packet broadcastPkt = Packet.json(OpCode.CHAT_MESSAGE_BROADCAST,
                Json.object("communityId", communityId, "sender", session.username(), "content", message));

        Set<String> members = community.members();
        List<UserSession> onlineUsers = userSessions.listOnlineUsers();

        for (UserSession u : onlineUsers) {
            // Only send to community members, exclude sender
            if (!members.contains(u.username())) continue;
            if (u.username().equals(session.username())) continue;

            try {
                u.channel().send(broadcastPkt);
            } catch (IOException e) {
                System.err.println("Broadcast to " + u.username() + " failed: " + e.getMessage());
                // Continue to other recipients
            }
        }
    }

    // ======================== Broadcast File Handlers ========================

    private void handleBroadcastFileUploadRequest(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.FILE_BROADCAST_UPLOAD_REQUEST, 1, MAX_COMMUNITY_BYTES * 2);
        String communityId;
        String fileName;
        String checksum;
        long fileSize;
        try {
            communityId = request.string("communityId");
            fileName = request.string("fileName");
            checksum = request.string("checksum");
            fileSize = request.longValue("fileSize");
        } catch (IOException e) {
            channel.send(Packet.status(OpCode.FILE_BROADCAST_UPLOAD_RESPONSE, request.sequence(), "INVALID_REQUEST"));
            return;
        }
        if (fileSize < 0 || fileSize > MAX_BROADCAST_FILE_SIZE) {
            channel.send(Packet.status(OpCode.FILE_BROADCAST_UPLOAD_RESPONSE, request.sequence(), "INVALID_SIZE"));
            return;
        }
        if (fileName.isBlank() || !checksum.matches("[0-9a-f]{64}")) {
            channel.send(Packet.status(OpCode.FILE_BROADCAST_UPLOAD_RESPONSE, request.sequence(), "INVALID_REQUEST"));
            return;
        }

        Community community = communityService.validateMembership(communityId, session.username());
        if (community == null) {
            channel.send(Packet.status(OpCode.FILE_BROADCAST_UPLOAD_RESPONSE, request.sequence(), "NOT_MEMBER"));
            return;
        }

        String fileId = UUID.randomUUID().toString();
        BroadcastFileMeta meta = new BroadcastFileMeta(fileId, session.username(), communityId,
                fileName, fileSize, checksum);
        broadcastFileRegistry.register(meta);
        String uploadToken = broadcastFileRegistry.createUploadToken(fileId);

        channel.send(Packet.reply(OpCode.FILE_BROADCAST_UPLOAD_RESPONSE, request.sequence(),
                Json.object("status", "OK", "fileId", fileId, "uploadToken", uploadToken)));
    }

    private void handleBroadcastUploadConnection(Packet startPacket) throws IOException {
        String uploadToken = startPacket.string("uploadToken");
        String fileId = startPacket.string("fileId");

        String tokenFileId = broadcastFileRegistry.consumeUploadToken(uploadToken);
        if (tokenFileId == null || !tokenFileId.equals(fileId)) {
            channel.send(Packet.failure(channel.lastReceivedSequence(), "Invalid or expired token"));
            return;
        }

        BroadcastFileMeta meta = broadcastFileRegistry.find(fileId).orElse(null);
        if (meta == null) {
            channel.send(Packet.failure(channel.lastReceivedSequence(), "File not found"));
            return;
        }

        socket.setSoTimeout(60000);

        try {
            try (OutputStream fileOut = fileStorageService.openTempForWrite(fileId)) {
                FileDataReader.receive(channel, fileOut, fileId, meta.fileSize(), meta.checksum(), null);
            }

            // Commit file
            fileStorageService.commit(fileId, meta.fileName());
            channel.send(Packet.status(OpCode.FILE_RESULT, channel.lastReceivedSequence(), "OK"));

        } catch (IOException | RuntimeException e) {
            fileStorageService.deleteTempIfExists(fileId);
            try {
                channel.send(Packet.failure(channel.lastReceivedSequence(), e.getMessage()));
            } catch (IOException resultError) {
                e.addSuppressed(resultError);
            }
            throw e;
        }

        // Broadcast offer to community members (excluding sender)
        Community community = communityService.validateMembership(meta.communityId(), meta.sender());
        if (community != null) {
            Packet offerPacket = Packet.json(OpCode.FILE_BROADCAST_OFFER,
                    Json.object("fileId", meta.fileId(), "sender", meta.sender(),
                            "communityId", meta.communityId(), "fileName", meta.fileName(),
                            "fileSize", meta.fileSize(), "checksum", meta.checksum()));

            Set<String> members = community.members();
            List<UserSession> onlineUsers = userSessions.listOnlineUsers();

            for (UserSession u : onlineUsers) {
                if (!members.contains(u.username())) continue;
                if (u.username().equals(meta.sender())) continue;

                meta.setRecipientState(u.username(), BroadcastFileMeta.RecipientState.PENDING);

                try {
                    u.channel().send(offerPacket);
                } catch (IOException e) {
                    System.err.println("Broadcast offer to " + u.username() + " failed: " + e.getMessage());
                }
            }
        }
    }

    private void handleBroadcastFileDecision(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.FILE_BROADCAST_DECISION, 1, MAX_COMMUNITY_BYTES);
        String fileId = request.string("fileId");
        String decision = request.string("decision");

        BroadcastFileMeta meta = broadcastFileRegistry.find(fileId).orElse(null);
        if (meta == null) {
            channel.send(Packet.status(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.sequence(), "NOT_FOUND"));
            return;
        }

        if (decision.equals("REJECT")) {
            meta.setRecipientState(session.username(), BroadcastFileMeta.RecipientState.REJECTED);
            channel.send(Packet.status(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.sequence(), "REJECTED"));
            return;
        }

        if (!decision.equals("ACCEPT")) {
            channel.send(Packet.status(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.sequence(), "INVALID_DECISION"));
            return;
        }

        // ACCEPT
        meta.setRecipientState(session.username(), BroadcastFileMeta.RecipientState.ACCEPTED);
        String downloadToken = broadcastFileRegistry.createDownloadToken(fileId, session.username());
        channel.send(Packet.reply(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.sequence(),
                Json.object("status", "OK", "downloadToken", downloadToken)));
    }

    private void handleBroadcastFileDownloadRequest(Packet request) throws IOException {
        // This is sent on the control connection to initiate a download (alternative path)
        // But we use dedicated data connections, so just acknowledge
        channel.send(Packet.status(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.sequence(), "USE_DATA_CONNECTION"));
    }

    private void handleBroadcastDownloadConnection(Packet startPacket) throws IOException {
        String downloadToken = startPacket.string("downloadToken");
        String fileId = startPacket.string("fileId");

        BroadcastFileRegistry.DownloadToken token = broadcastFileRegistry.consumeDownloadToken(downloadToken);
        if (token == null || !token.fileId().equals(fileId)) {
            channel.send(Packet.failure(channel.lastReceivedSequence(), "Invalid or expired token"));
            return;
        }

        BroadcastFileMeta meta = broadcastFileRegistry.find(fileId).orElse(null);
        if (meta == null) {
            channel.send(Packet.failure(channel.lastReceivedSequence(), "File not found"));
            return;
        }

        meta.setRecipientState(token.username(), BroadcastFileMeta.RecipientState.DOWNLOADING);
        socket.setSoTimeout(60000);

        try {
            // Stream the file using FileSender-compatible protocol
            java.io.File storedFile = fileStorageService.committedPath(fileId, meta.fileName()).toFile();
            FileSender sender = new FileSender(storedFile, fileId, channel);
            sender.send();

            sender.awaitResult();
            meta.setRecipientState(token.username(), BroadcastFileMeta.RecipientState.COMPLETED);
        } catch (IOException e) {
            meta.setRecipientState(token.username(), BroadcastFileMeta.RecipientState.FAILED);
            throw e;
        }
    }
}
