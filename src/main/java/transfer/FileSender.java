package transfer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import protocols.FileTransferMeta;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;

public class FileSender {

    public static final int CHUNK_SIZE = 64 * 1024;

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long bytesSent, long totalBytes, int percent);
    }

    private final File file;
    private final String transferId;
    private final OutputStream output;
    private final FileTransferSession session;
    private final ProgressListener listener;

    public FileSender(File file, String transferId, OutputStream output) {
        this(file, transferId, output, null, null);
    }

    public FileSender(File file, String transferId, OutputStream output,
                      FileTransferSession session, ProgressListener listener) {
        this.file = Objects.requireNonNull(file, "file must not be null");
        this.transferId = Objects.requireNonNull(transferId, "transferId must not be null");
        this.output = Objects.requireNonNull(output, "output must not be null");
        this.session = session;
        this.listener = listener;
    }

    public String send() throws IOException {
        validateFile();

        long fileSize = file.length();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available in JVM", e);
        }

        if (session != null && session.state() == FileTransferSession.State.ACCEPTED) {
            session.startTransfer();
        }

        long totalBytesSent = 0;
        int chunkIndex = 0;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);

                byte[] chunkData;
                if (bytesRead == buffer.length) {
                    chunkData = buffer.clone();
                } else {
                    chunkData = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunkData, 0, bytesRead);
                }

                Packet chunkPacket = new Packet(OpCode.FILE_CHUNK, chunkIndex, chunkData);
                PacketCodec.write(output, chunkPacket);

                totalBytesSent += bytesRead;
                chunkIndex++;

                if (session != null) {
                    session.addBytesTransferred(bytesRead);
                }
                if (listener != null) {
                    int percent = fileSize > 0 ? (int) (totalBytesSent * 100 / fileSize) : 100;
                    listener.onProgress(totalBytesSent, fileSize, percent);
                }
            }
        } catch (IOException e) {
            if (session != null && !session.isTerminal()) {
                session.fail();
            }
            throw e;
        }

        if (totalBytesSent != fileSize) {
            if (session != null && !session.isTerminal()) {
                session.fail();
            }
            throw new IOException("File size changed during transfer: expected "
                    + fileSize + " bytes, sent " + totalBytesSent + " bytes");
        }

        byte[] hashBytes = digest.digest();
        String checksumHex = HexFormat.of().formatHex(hashBytes);

        byte[] endPayload = checksumHex.getBytes(StandardCharsets.UTF_8);
        Packet endPacket = new Packet(OpCode.FILE_END, chunkIndex, endPayload);
        PacketCodec.write(output, endPacket);

        return checksumHex;
    }

    private void validateFile() throws IOException {
        if (!file.exists()) {
            throw new IOException("File not found: " + file.getAbsolutePath());
        }
        if (!file.isFile()) {
            throw new IOException("Target is not a regular file: " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            throw new IOException("Cannot read file (permission denied): " + file.getAbsolutePath());
        }
        if (file.length() > FileTransferMeta.MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds maximum allowed limit: "
                    + file.length() + " > " + FileTransferMeta.MAX_FILE_SIZE);
        }
    }

    public File getFile() {
        return file;
    }

    public String getTransferId() {
        return transferId;
    }
}
