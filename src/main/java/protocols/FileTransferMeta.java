package protocols;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

    public byte[] toBytes() {
        byte[] idBytes       = transferId.getBytes(StandardCharsets.UTF_8);
        byte[] senderBytes   = sender.getBytes(StandardCharsets.UTF_8);
        byte[] receiverBytes = receiver.getBytes(StandardCharsets.UTF_8);
        byte[] nameBytes     = fileName.getBytes(StandardCharsets.UTF_8);
        byte[] checksumBytes = checksum.getBytes(StandardCharsets.UTF_8);

        int totalSize = (4 + idBytes.length)
                      + (4 + senderBytes.length)
                      + (4 + receiverBytes.length)
                      + (4 + nameBytes.length)
                      + 8 + 4 + checksumBytes.length;

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        writeString(buffer, idBytes);
        writeString(buffer, senderBytes);
        writeString(buffer, receiverBytes);
        writeString(buffer, nameBytes);
        buffer.putLong(fileSize);
        writeString(buffer, checksumBytes);

        return buffer.array();
    }

    public static FileTransferMeta fromBytes(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        try {
            String transferId = readString(buffer);
            String sender     = readString(buffer);
            String receiver   = readString(buffer);
            String fileName   = readString(buffer);
            long   fileSize   = buffer.getLong();
            String checksum   = readString(buffer);
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("Unexpected trailing metadata bytes");
            }

            return new FileTransferMeta(transferId, sender, receiver, fileName, fileSize, checksum);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid FileTransferMeta data", e);
        }
    }

    private static void writeString(ByteBuffer buffer, byte[] strBytes) {
        buffer.putInt(strBytes.length);
        buffer.put(strBytes);
    }

    private static String readString(ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) {
            throw new IllegalArgumentException("Invalid string length: " + length);
        }
        byte[] strBytes = new byte[length];
        buffer.get(strBytes);
        return new String(strBytes, StandardCharsets.UTF_8);
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
