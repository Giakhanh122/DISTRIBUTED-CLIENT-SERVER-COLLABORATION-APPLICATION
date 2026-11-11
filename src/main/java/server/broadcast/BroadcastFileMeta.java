package server.broadcast;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metadata for a broadcast file stored on the server.
 */
public class BroadcastFileMeta {
    public enum RecipientState {
        PENDING, ACCEPTED, REJECTED, DOWNLOADING, COMPLETED, FAILED
    }

    private final String fileId;
    private final String sender;
    private final String communityId;
    private final String fileName;
    private final long fileSize;
    private final String checksum;
    private final Instant uploadTimestamp;
    private final Map<String, RecipientState> recipientStates = new ConcurrentHashMap<>();

    public BroadcastFileMeta(String fileId, String sender, String communityId,
                             String fileName, long fileSize, String checksum) {
        this.fileId = fileId;
        this.sender = sender;
        this.communityId = communityId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.checksum = checksum;
        this.uploadTimestamp = Instant.now();
    }

    public String fileId() { return fileId; }
    public String sender() { return sender; }
    public String communityId() { return communityId; }
    public String fileName() { return fileName; }
    public long fileSize() { return fileSize; }
    public String checksum() { return checksum; }
    public Instant uploadTimestamp() { return uploadTimestamp; }

    public void setRecipientState(String username, RecipientState state) {
        recipientStates.put(username, state);
    }

    public RecipientState getRecipientState(String username) {
        return recipientStates.getOrDefault(username, RecipientState.PENDING);
    }

    public Map<String, RecipientState> recipientStates() {
        return Map.copyOf(recipientStates);
    }
}
