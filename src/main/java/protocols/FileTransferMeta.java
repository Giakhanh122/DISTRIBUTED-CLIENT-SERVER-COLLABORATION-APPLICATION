package protocols;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.UUID;

public final class FileTransferMeta {
    public static final long MAX_FILE_SIZE = 500L * 1024 * 1024;

    private final String transferId;
    private final String sender;
    private final String receiver;
    private final String fileName;
    private final long fileSize;
    private final String checksum;

    public FileTransferMeta(String transferId, String sender, String receiver,
                            String fileName, long fileSize, String checksum) {
        if (transferId == null || transferId.isEmpty()) {
            throw new IllegalArgumentException("transferId must not be null or empty");
        }
        if (!UUID.fromString(transferId).toString().equals(transferId)) {
            throw new IllegalArgumentException("transferId must be a canonical UUID");
        }
        if (sender == null || sender.isEmpty()) {
            throw new IllegalArgumentException("sender must not be null or empty");
        }
        if (receiver == null || receiver.isEmpty()) {
            throw new IllegalArgumentException("receiver must not be null or empty");
        }
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName must not be null or empty");
        }
        if (fileSize < 0) {
            throw new IllegalArgumentException("fileSize must be >= 0, got: " + fileSize);
        }
        if (fileSize > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "fileSize exceeds limit: " + fileSize + " > " + MAX_FILE_SIZE);
        }
        if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksum must be a lowercase SHA-256 hex string");
        }

        this.transferId = transferId;
        this.sender = sender;
        this.receiver = receiver;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.checksum = checksum;
    }

    public static FileTransferMeta create(String sender, String receiver,
                                          String fileName, long fileSize, String checksum) {
        String id = UUID.randomUUID().toString();
        return new FileTransferMeta(id, sender, receiver, fileName, fileSize, checksum);
    }

    public String transferId() { return transferId; }
    public String sender()     { return sender; }
    public String receiver()   { return receiver; }
    public String fileName()   { return fileName; }
    public long   fileSize()   { return fileSize; }
    public String checksum()  { return checksum; }

    public JsonObject toJson() {
        return Json.object("transferId", transferId, "sender", sender, "receiver", receiver,
                "fileName", fileName, "fileSize", fileSize, "checksum", checksum);
    }

    public static FileTransferMeta fromJson(JsonObject payload) throws IOException {
        try {
            return new FileTransferMeta(Json.string(payload, "transferId"),
                    Json.string(payload, "sender"), Json.string(payload, "receiver"),
                    Json.string(payload, "fileName"), Json.longValue(payload, "fileSize"),
                    Json.string(payload, "checksum"));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid file metadata", e);
        }
    }

    @Override
    public String toString() {
        return "FileTransferMeta{"
                + "transferId='" + transferId + '\''
                + ", sender='" + sender + '\''
                + ", receiver='" + receiver + '\''
                + ", fileName='" + fileName + '\''
                + ", fileSize=" + fileSize
                + ", checksum='" + checksum + '\''
                + '}';
    }
}
