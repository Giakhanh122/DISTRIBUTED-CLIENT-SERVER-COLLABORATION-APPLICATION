package transfer;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import protocols.OpCode;
import protocols.Packet;
import network.PacketChannel;

/** Receives exactly fileSize raw bytes, followed by a FILE_END JSON message. */
public final class FileDataReader {
    @FunctionalInterface
    public interface ProgressListener {
        void onChunk(int chunkLength, long totalReceived);
    }

    private FileDataReader() {}

    public static void receive(PacketChannel channel, OutputStream destination,
                               String transferId, long expectedSize, String expectedChecksum,
                               ProgressListener progress) throws IOException {
        MessageDigest digest = sha256();
        long received = 0;
        byte[] buffer = new byte[FileSender.CHUNK_SIZE];
        while (received < expectedSize) {
            int length = (int) Math.min(buffer.length, expectedSize - received);
            channel.readRaw(buffer, 0, length);
            destination.write(buffer, 0, length);
            digest.update(buffer, 0, length);
            received += length;
            if (progress != null) progress.onChunk(length, received);
        }

        Packet end = channel.read();
        if (end == null || end.op() != OpCode.FILE_END) throw new IOException("Missing FILE_END");
        if (!end.string("transferId").equals(transferId)) {
            throw new IOException("FILE_END does not match transfer");
        }
        String checksum = HexFormat.of().formatHex(digest.digest());
        if (!checksum.equals(expectedChecksum) || !checksum.equals(end.string("checksum"))) {
            throw new IOException("Checksum mismatch");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
