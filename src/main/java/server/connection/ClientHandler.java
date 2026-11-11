package server.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import protocols.PacketValidator;
import server.auth.AuthService;
import server.broadcast.BroadcastFileMeta;
import server.broadcast.BroadcastFileRegistry;
import server.community.Community;
import server.community.CommunityService;
import server.session.ChatSession;
import server.session.ChatSessionRegistry;
import server.session.UserSession;
import server.session.UserSessionRegistry;
import server.storage.FileStorageService;
import transfer.FileSender;

public class ClientHandler implements Runnable {
    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private static final int MAX_AUTH_BYTES = 4096;
    private static final int MAX_COMMUNITY_BYTES = 4096;
    private static final long MAX_BROADCAST_FILE_SIZE = 500L * 1024 * 1024;
    private static final int CHUNK_SIZE = 64 * 1024;

    private static final ExecutorService downloadPool = Executors.newFixedThreadPool(10);

    private final Socket socket;
    private final UserSessionRegistry userSessions;
    private final ChatSessionRegistry chatSessions;
    private final AuthService authService;
    private final CommunityService communityService;
    private final BroadcastFileRegistry broadcastFileRegistry;
    private final FileStorageService fileStorageService;

    private UserSession session;
    private OutputStream output;

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

            InputStream input = socket.getInputStream();
            this.output = socket.getOutputStream();

            Packet first = PacketCodec.read(input);
            if (first == null) return;

            // Demultiplex: HELLO = normal client, upload/download start = file data connection
            if (first.op() == OpCode.FILE_BROADCAST_UPLOAD_START) {
                handleBroadcastUploadConnection(first, input);
                return;
            }
            if (first.op() == OpCode.FILE_BROADCAST_DOWNLOAD_START) {
                handleBroadcastDownloadConnection(first, input);
                return;
            }

            // Normal client session
            if (first.op() != OpCode.HELLO || first.payloadLength() != 0) {
                System.err.println("Invalid or missing HELLO from " + address);
                return;
            }

            Packet response = new Packet(OpCode.HELLO_ACK, first.requestId(), new byte[0]);
            sendPacket(response);
            System.out.println("Handshake successful with " + address);

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

    // ======================== Community Handlers ========================

    private void handleCommunityList(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_LIST_REQUEST, 0, 0);
        String result = communityService.listCommunities(session.username());
        sendPacket(Packet.text(OpCode.COMMUNITY_LIST_RESPONSE, request.requestId(), result));
    }

    private void handleCommunityCreate(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_CREATE_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String name = request.text();
        String result = communityService.createCommunity(name, session.username());
        sendPacket(Packet.text(OpCode.COMMUNITY_CREATE_RESPONSE, request.requestId(), result));
    }

    private void handleCommunityJoin(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_JOIN_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.text();
        String result = communityService.joinCommunity(communityId, session.username());
        sendPacket(Packet.text(OpCode.COMMUNITY_JOIN_RESPONSE, request.requestId(), result));
    }

    private void handleCommunityLeave(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_LEAVE_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.text();
        String result = communityService.leaveCommunity(communityId, session.username());
        sendPacket(Packet.text(OpCode.COMMUNITY_LEAVE_RESPONSE, request.requestId(), result));
    }

    private void handleCommunityMembers(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_MEMBERS_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.text();
        String result = communityService.listMembers(communityId, session.username());
        sendPacket(Packet.text(OpCode.COMMUNITY_MEMBERS_RESPONSE, request.requestId(), result));
    }

    private void handleCommunitySelect(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_SELECT_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.text();
        String result = communityService.selectCommunity(communityId, session.username());
        sendPacket(Packet.text(OpCode.COMMUNITY_SELECT_RESPONSE, request.requestId(), result));
    }

    private void handleCommunityInfo(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_INFO_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.text();
        String result = communityService.communityInfo(communityId, session.username());
        sendPacket(Packet.text(OpCode.COMMUNITY_INFO_RESPONSE, request.requestId(), result));
    }

    // ======================== Broadcast Message (community-scoped) ========================

    private void handleBroadcastMessage(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.CHAT_MESSAGE_BROADCAST, 0, MAX_CHAT_BYTES);
        String payload = request.text();

        // Payload format: communityId\n<message>
        int sep = payload.indexOf('\n');
        if (sep <= 0) {
            throw new IOException("Invalid broadcast payload");
        }
        String communityId = payload.substring(0, sep);
        String message = payload.substring(sep + 1);

        // Validate membership
        if (communityService == null) {
            throw new IOException("Community service not available");
        }
        Community community = communityService.validateMembership(communityId, session.username());
        if (community == null) {
            throw new IOException("Not a member of the target community");
        }

        // Build the broadcast packet (without communityId, just the message for recipients)
        Packet broadcastPkt = Packet.text(OpCode.CHAT_MESSAGE_BROADCAST, request.requestId(), message);

        Set<String> members = community.members();
        List<UserSession> onlineUsers = userSessions.listOnlineUsers();

        for (UserSession u : onlineUsers) {
            // Only send to community members, exclude sender
            if (!members.contains(u.username())) continue;
            if (u.username().equals(session.username())) continue;

            try {
                synchronized (u.clientSocket().getOutputStream()) {
                    PacketCodec.write(u.clientSocket().getOutputStream(), broadcastPkt);
                }
            } catch (IOException e) {
                System.err.println("Broadcast to " + u.username() + " failed: " + e.getMessage());
                // Continue to other recipients
            }
        }
    }

    // ======================== Broadcast File Handlers ========================

    private void handleBroadcastFileUploadRequest(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.FILE_BROADCAST_UPLOAD_REQUEST, 1, MAX_COMMUNITY_BYTES * 2);
        // Payload: communityId\nfileName\nfileSize\nchecksum
        String[] parts = request.text().split("\n", -1);
        if (parts.length != 4) {
            sendPacket(Packet.text(OpCode.FILE_BROADCAST_UPLOAD_RESPONSE, request.requestId(), "INVALID_REQUEST"));
            return;
        }
        String communityId = parts[0];
        String fileName = parts[1];
        long fileSize;
        try {
            fileSize = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            sendPacket(Packet.text(OpCode.FILE_BROADCAST_UPLOAD_RESPONSE, request.requestId(), "INVALID_SIZE"));
            return;
        }
        String checksum = parts[3];

        if (fileSize <= 0 || fileSize > MAX_BROADCAST_FILE_SIZE) {
            sendPacket(Packet.text(OpCode.FILE_BROADCAST_UPLOAD_RESPONSE, request.requestId(), "INVALID_SIZE"));
            return;
        }

        Community community = communityService.validateMembership(communityId, session.username());
        if (community == null) {
            sendPacket(Packet.text(OpCode.FILE_BROADCAST_UPLOAD_RESPONSE, request.requestId(), "NOT_MEMBER"));
            return;
        }

        String fileId = UUID.randomUUID().toString();
        BroadcastFileMeta meta = new BroadcastFileMeta(fileId, session.username(), communityId,
                fileName, fileSize, checksum);
        broadcastFileRegistry.register(meta);
        String uploadToken = broadcastFileRegistry.createUploadToken(fileId);

        // Response: OK\nfileId\nuploadToken
        sendPacket(Packet.text(OpCode.FILE_BROADCAST_UPLOAD_RESPONSE, request.requestId(),
                "OK\n" + fileId + "\n" + uploadToken));
    }

    private void handleBroadcastUploadConnection(Packet startPacket, InputStream input) throws IOException {
        // Payload: uploadToken\nfileId
        String[] parts = startPacket.text().split("\n", -1);
        if (parts.length != 2) {
            sendPacket(Packet.text(OpCode.FILE_RESULT, 0, "FAIL: Invalid upload start"));
            return;
        }
        String uploadToken = parts[0];
        String fileId = parts[1];

        String tokenFileId = broadcastFileRegistry.consumeUploadToken(uploadToken);
        if (tokenFileId == null || !tokenFileId.equals(fileId)) {
            sendPacket(Packet.text(OpCode.FILE_RESULT, 0, "FAIL: Invalid or expired token"));
            return;
        }

        BroadcastFileMeta meta = broadcastFileRegistry.find(fileId).orElse(null);
        if (meta == null) {
            sendPacket(Packet.text(OpCode.FILE_RESULT, 0, "FAIL: File not found"));
            return;
        }

        socket.setSoTimeout(60000);

        // Receive file chunks using the same protocol as P2P: FILE_CHUNK packets then FILE_END
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }

        long received = 0;
        int expectedIndex = 0;
        String remoteChecksum;

        try (OutputStream fileOut = fileStorageService.openTempForWrite(fileId)) {
            while (true) {
                Packet packet = PacketCodec.read(input);
                if (packet == null) {
                    fileStorageService.deleteTempIfExists(fileId);
                    sendPacket(Packet.text(OpCode.FILE_RESULT, 0, "FAIL: Connection closed"));
                    return;
                }
                if (packet.op() == OpCode.FILE_CHUNK) {
                    if (packet.requestId() != expectedIndex) {
                        fileStorageService.deleteTempIfExists(fileId);
                        sendPacket(Packet.text(OpCode.FILE_RESULT, 0, "FAIL: Out of order chunk"));
                        return;
                    }
                    byte[] data = packet.data();
                    if (data.length <= 0 || data.length > CHUNK_SIZE) {
                        fileStorageService.deleteTempIfExists(fileId);
                        sendPacket(Packet.text(OpCode.FILE_RESULT, 0, "FAIL: Invalid chunk size"));
                        return;
                    }
                    if (received + data.length > meta.fileSize()) {
                        fileStorageService.deleteTempIfExists(fileId);
                        sendPacket(Packet.text(OpCode.FILE_RESULT, 0, "FAIL: Exceeds declared size"));
                        return;
                    }
                    fileOut.write(data);
                    digest.update(data);
                    received += data.length;
                    expectedIndex++;
                } else if (packet.op() == OpCode.FILE_END) {
                    remoteChecksum = packet.text();
                    break;
                } else {
                    fileStorageService.deleteTempIfExists(fileId);
                    sendPacket(Packet.text(OpCode.FILE_RESULT, 0, "FAIL: Unexpected packet " + packet.op()));
                    return;
                }
            }
        }

        // Verify
        String computedChecksum = HexFormat.of().formatHex(digest.digest());
        if (received != meta.fileSize()) {
            fileStorageService.deleteTempIfExists(fileId);
            sendPacket(Packet.text(OpCode.FILE_RESULT, 0,
                    "FAIL: Size mismatch, expected " + meta.fileSize() + " got " + received));
            return;
        }
        if (!computedChecksum.equals(meta.checksum()) || !computedChecksum.equals(remoteChecksum)) {
            fileStorageService.deleteTempIfExists(fileId);
            sendPacket(Packet.text(OpCode.FILE_RESULT, 0, "FAIL: Checksum mismatch"));
            return;
        }

        // Commit file
        fileStorageService.commit(fileId, meta.fileName());
        sendPacket(Packet.text(OpCode.FILE_RESULT, 0, "OK"));

        // Broadcast offer to community members (excluding sender)
        Community community = communityService.validateMembership(meta.communityId(), meta.sender());
        if (community != null) {
            // Offer payload: fileId\tsender\tfilename\tfileSize\tchecksum
            String offerPayload = meta.fileId() + "\t" + meta.sender() + "\t" +
                    meta.fileName() + "\t" + meta.fileSize() + "\t" + meta.checksum();
            Packet offerPacket = Packet.text(OpCode.FILE_BROADCAST_OFFER, 0, offerPayload);

            Set<String> members = community.members();
            List<UserSession> onlineUsers = userSessions.listOnlineUsers();

            for (UserSession u : onlineUsers) {
                if (!members.contains(u.username())) continue;
                if (u.username().equals(meta.sender())) continue;

                meta.setRecipientState(u.username(), BroadcastFileMeta.RecipientState.PENDING);

                try {
                    synchronized (u.clientSocket().getOutputStream()) {
                        PacketCodec.write(u.clientSocket().getOutputStream(), offerPacket);
                    }
                } catch (IOException e) {
                    System.err.println("Broadcast offer to " + u.username() + " failed: " + e.getMessage());
                }
            }
        }
    }

    private void handleBroadcastFileDecision(Packet request) throws IOException {
        PacketValidator.validateRequest(request, OpCode.FILE_BROADCAST_DECISION, 1, MAX_COMMUNITY_BYTES);
        // Payload: fileId\nACCEPT or fileId\nREJECT
        String[] parts = request.text().split("\n", -1);
        if (parts.length != 2) {
            sendPacket(Packet.text(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.requestId(), "INVALID_DECISION"));
            return;
        }
        String fileId = parts[0];
        String decision = parts[1];

        BroadcastFileMeta meta = broadcastFileRegistry.find(fileId).orElse(null);
        if (meta == null) {
            sendPacket(Packet.text(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.requestId(), "NOT_FOUND"));
            return;
        }

        if (decision.equals("REJECT")) {
            meta.setRecipientState(session.username(), BroadcastFileMeta.RecipientState.REJECTED);
            sendPacket(Packet.text(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.requestId(), "REJECTED"));
            return;
        }

        if (!decision.equals("ACCEPT")) {
            sendPacket(Packet.text(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.requestId(), "INVALID_DECISION"));
            return;
        }

        // ACCEPT
        meta.setRecipientState(session.username(), BroadcastFileMeta.RecipientState.ACCEPTED);
        String downloadToken = broadcastFileRegistry.createDownloadToken(fileId, session.username());
        sendPacket(Packet.text(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.requestId(),
                "OK\n" + downloadToken));
    }

    private void handleBroadcastFileDownloadRequest(Packet request) throws IOException {
        // This is sent on the control connection to initiate a download (alternative path)
        // But we use dedicated data connections, so just acknowledge
        sendPacket(Packet.text(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.requestId(), "USE_DATA_CONNECTION"));
    }

    private void handleBroadcastDownloadConnection(Packet startPacket, InputStream input) throws IOException {
        // Payload: downloadToken\nfileId
        String[] parts = startPacket.text().split("\n", -1);
        if (parts.length != 2) {
            sendPacket(Packet.text(OpCode.FILE_RESULT, 0, "FAIL: Invalid download start"));
            return;
        }
        String downloadToken = parts[0];
        String fileId = parts[1];

        BroadcastFileRegistry.DownloadToken token = broadcastFileRegistry.consumeDownloadToken(downloadToken);
        if (token == null || !token.fileId().equals(fileId)) {
            sendPacket(Packet.text(OpCode.FILE_RESULT, 0, "FAIL: Invalid or expired token"));
            return;
        }

        BroadcastFileMeta meta = broadcastFileRegistry.find(fileId).orElse(null);
        if (meta == null) {
            sendPacket(Packet.text(OpCode.FILE_RESULT, 0, "FAIL: File not found"));
            return;
        }

        meta.setRecipientState(token.username(), BroadcastFileMeta.RecipientState.DOWNLOADING);
        socket.setSoTimeout(60000);

        try {
            // Stream the file using FileSender-compatible protocol
            java.io.File storedFile = fileStorageService.committedPath(fileId, meta.fileName()).toFile();
            FileSender sender = new FileSender(storedFile, fileId, output);
            sender.send();

            // Wait for FILE_RESULT from client
            Packet result = PacketCodec.read(input);
            if (result != null && result.op() == OpCode.FILE_RESULT && result.text().equals("OK")) {
                meta.setRecipientState(token.username(), BroadcastFileMeta.RecipientState.COMPLETED);
            } else {
                meta.setRecipientState(token.username(), BroadcastFileMeta.RecipientState.FAILED);
            }
        } catch (IOException e) {
            meta.setRecipientState(token.username(), BroadcastFileMeta.RecipientState.FAILED);
            throw e;
        }
    }
}
