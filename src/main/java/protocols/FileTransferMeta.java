package protocols;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class FileTransferMeta {
    private final String transferId;
    private final String sender;
    private final String receiver;
    private final String fileName;
    private final long fileSize;

    public FileTransferMeta(String transferId, String sender, String receiver, String fileName, long fileSize) {
        this.transferId = transferId;
        this.sender = sender;
        this.receiver = receiver;
        this.fileName = fileName;
        this.fileSize = fileSize;
    }

    public static FileTransferMeta create(String sender, String receiver, String fileName, long fileSize) {
        return new FileTransferMeta(UUID.randomUUID().toString(), sender, receiver, fileName, fileSize);
    }

    public String transferId() { return transferId; }
    public String sender() { return sender; }
    public String receiver() { return receiver; }
    public String fileName() { return fileName; }
    public long fileSize() { return fileSize; }

    public byte[] toBytes() {
        byte[] idBytes = transferId.getBytes(StandardCharsets.UTF_8);
        byte[] senderBytes = sender.getBytes(StandardCharsets.UTF_8);
        byte[] receiverBytes = receiver.getBytes(StandardCharsets.UTF_8);
        byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buf = ByteBuffer.allocate(16 + idBytes.length + senderBytes.length + receiverBytes.length + nameBytes.length + 8);
        buf.putInt(idBytes.length).put(idBytes);
        buf.putInt(senderBytes.length).put(senderBytes);
        buf.putInt(receiverBytes.length).put(receiverBytes);
        buf.putInt(nameBytes.length).put(nameBytes);
        buf.putLong(fileSize);
        return buf.array();
    }

    public static FileTransferMeta fromBytes(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        String id = readStr(buf);
        String sender = readStr(buf);
        String receiver = readStr(buf);
        String fileName = readStr(buf);
        long size = buf.getLong();
        return new FileTransferMeta(id, sender, receiver, fileName, size);
    }

    private static String readStr(ByteBuffer buf) {
        byte[] bytes = new byte[buf.getInt()];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
