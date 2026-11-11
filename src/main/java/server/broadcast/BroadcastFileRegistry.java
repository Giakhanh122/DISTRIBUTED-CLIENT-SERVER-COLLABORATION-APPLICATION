package server.broadcast;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry tracking broadcast file metadata and upload/download tokens.
 */
public class BroadcastFileRegistry {
    private final ConcurrentHashMap<String, BroadcastFileMeta> files = new ConcurrentHashMap<>();
    // token -> fileId mapping for secure upload/download
    private final ConcurrentHashMap<String, String> uploadTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DownloadToken> downloadTokens = new ConcurrentHashMap<>();

    public record DownloadToken(String fileId, String username) {}

    public void register(BroadcastFileMeta meta) {
        files.put(meta.fileId(), meta);
    }

    public Optional<BroadcastFileMeta> find(String fileId) {
        return Optional.ofNullable(files.get(fileId));
    }

    public String createUploadToken(String fileId) {
        String token = UUID.randomUUID().toString();
        uploadTokens.put(token, fileId);
        return token;
    }

    public String consumeUploadToken(String token) {
        return uploadTokens.remove(token);
    }

    public String createDownloadToken(String fileId, String username) {
        String token = UUID.randomUUID().toString();
        downloadTokens.put(token, new DownloadToken(fileId, username));
        return token;
    }

    public DownloadToken consumeDownloadToken(String token) {
        return downloadTokens.remove(token);
    }
}
