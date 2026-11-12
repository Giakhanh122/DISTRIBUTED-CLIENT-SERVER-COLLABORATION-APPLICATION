package server.broadcast;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stored file metadata and one-use tokens for dedicated data connections. */
public final class BroadcastFileRegistry {
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(5);
    private record Token<T>(T value, Instant expiresAt) {
        boolean expired() { return !Instant.now().isBefore(expiresAt); }
    }
    public record DownloadToken(String fileId, String username) {}

    private final ConcurrentHashMap<String, BroadcastFileMeta> files = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Token<String>> uploadTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Token<DownloadToken>> downloadTokens = new ConcurrentHashMap<>();

    public void register(BroadcastFileMeta meta) {
        purgeExpired();
        files.put(meta.fileId(), meta);
    }

    public Optional<BroadcastFileMeta> find(String fileId) {
        return Optional.ofNullable(files.get(fileId));
    }

    public String createUploadToken(String fileId) {
        purgeExpired();
        String token = UUID.randomUUID().toString();
        uploadTokens.put(token, new Token<>(fileId, Instant.now().plus(TOKEN_LIFETIME)));
        return token;
    }

    public String consumeUploadToken(String token) {
        Token<String> entry = uploadTokens.remove(token);
        if (entry == null) return null;
        if (entry.expired()) { discard(entry.value()); return null; }
        return entry.value();
    }

    public String createDownloadToken(String fileId, String username) {
        purgeExpired();
        DownloadToken value = new DownloadToken(fileId, username);
        downloadTokens.values().removeIf(entry -> entry.value().equals(value));
        String token = UUID.randomUUID().toString();
        downloadTokens.put(token, new Token<>(value, Instant.now().plus(TOKEN_LIFETIME)));
        return token;
    }

    public DownloadToken consumeDownloadToken(String token) {
        Token<DownloadToken> entry = downloadTokens.remove(token);
        return entry == null || entry.expired() ? null : entry.value();
    }

    public void discard(String fileId) {
        files.remove(fileId);
        uploadTokens.values().removeIf(entry -> entry.value().equals(fileId));
        downloadTokens.values().removeIf(entry -> entry.value().fileId().equals(fileId));
    }

    private void purgeExpired() {
        uploadTokens.forEach((key, entry) -> {
            if (entry.expired() && uploadTokens.remove(key, entry)) files.remove(entry.value());
        });
        downloadTokens.values().removeIf(Token::expired);
    }
}
