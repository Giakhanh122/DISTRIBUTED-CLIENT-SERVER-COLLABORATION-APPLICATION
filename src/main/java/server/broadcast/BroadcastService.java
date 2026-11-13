package server.broadcast;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import network.PacketChannel;
import protocols.FileTransferMeta;
import protocols.Json;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import protocols.PacketValidator;
import server.community.Community;
import server.community.CommunityService;
import server.session.UserSession;
import server.session.UserSessionRegistry;
import server.storage.FileStorageService;
import transfer.FileSender;
import transfer.FileDataReader;

/** Community broadcast delivery, file storage and dedicated data-connection operations. */
public final class BroadcastService {
    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private static final int MAX_COMMUNITY_BYTES = 4096;
    private final UserSessionRegistry userSessions;
    private final CommunityService communityService;
    private final BroadcastFileRegistry broadcastFileRegistry;
    private final FileStorageService fileStorageService;

    public BroadcastService(UserSessionRegistry userSessions, CommunityService communityService,
                                BroadcastFileRegistry broadcastFileRegistry, FileStorageService fileStorageService) {
        this.userSessions = userSessions;
        this.communityService = communityService;
        this.broadcastFileRegistry = broadcastFileRegistry;
        this.fileStorageService = fileStorageService;
    }

    public void handleBroadcastMessage(Packet request, UserSession session) throws IOException {
        PacketValidator.validateRequest(request, OpCode.CHAT_MESSAGE_BROADCAST, 1, PacketCodec.MAX_JSON_LENGTH);
        String communityId = request.string("communityId");
        String message = request.string("content");
        if (message.getBytes(StandardCharsets.UTF_8).length > MAX_CHAT_BYTES) {
            throw new IOException("Message exceeds limit");
        }

        // Validate membership
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

    public void handleBroadcastFileUploadRequest(Packet request, UserSession session) throws IOException {
        PacketChannel channel = session.channel();
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
        if (fileSize < 0 || fileSize > FileTransferMeta.MAX_FILE_SIZE) {
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

    public void handleBroadcastUploadConnection(Packet startPacket, PacketChannel channel) throws IOException {
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

        channel.socket().setSoTimeout(60000);

        try {
            try (OutputStream fileOut = fileStorageService.openTempForWrite(fileId)) {
                FileDataReader.receive(channel, fileOut, fileId, meta.fileSize(), meta.checksum(), null);
            }

            // Commit file
            fileStorageService.commit(fileId, meta.fileName());
            channel.send(Packet.status(OpCode.FILE_RESULT, channel.lastReceivedSequence(), "OK"));

        } catch (IOException | RuntimeException e) {
            fileStorageService.deleteTempIfExists(fileId);
            broadcastFileRegistry.discard(fileId);
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

    public void handleBroadcastFileDecision(Packet request, UserSession session) throws IOException {
        PacketChannel channel = session.channel();
        PacketValidator.validateRequest(request, OpCode.FILE_BROADCAST_DECISION, 1, MAX_COMMUNITY_BYTES);
        String fileId = request.string("fileId");
        String decision = request.string("decision");

        BroadcastFileMeta meta = broadcastFileRegistry.find(fileId).orElse(null);
        if (meta == null) {
            channel.send(Packet.status(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.sequence(), "NOT_FOUND"));
            return;
        }

        if (!mayDownload(meta, session.username())) {
            channel.send(Packet.status(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.sequence(), "NOT_AUTHORIZED"));
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

    private boolean mayDownload(BroadcastFileMeta meta, String username) {
        return meta.isRecipient(username)
                && communityService.validateMembership(meta.communityId(), username) != null;
    }

    public void handleBroadcastFileDownloadRequest(Packet request, UserSession session) throws IOException {
        PacketChannel channel = session.channel();
        // This is sent on the control connection to initiate a download (alternative path)
        // But we use dedicated data connections, so just acknowledge
        channel.send(Packet.status(OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE, request.sequence(), "USE_DATA_CONNECTION"));
    }

    public void handleBroadcastDownloadConnection(Packet startPacket, PacketChannel channel) throws IOException {
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

        if (!mayDownload(meta, token.username())) {
            channel.send(Packet.failure(channel.lastReceivedSequence(), "No longer a recipient of this community file"));
            return;
        }

        meta.setRecipientState(token.username(), BroadcastFileMeta.RecipientState.DOWNLOADING);
        channel.socket().setSoTimeout(60000);

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
