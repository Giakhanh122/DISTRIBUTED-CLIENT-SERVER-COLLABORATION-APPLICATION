package transfer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import protocols.FileTransferMeta;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import protocols.PacketValidator;

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
        try {
            return sendFile();
        } catch (IOException | RuntimeException e) {
            if (session != null) {
                session.fail();
            }
            throw e;
        }
    }

    private String sendFile() throws IOException {
        validateFile(file);

        long fileSize = file.length();
        if (session != null && (!transferId.equals(session.transferId())
                || fileSize != session.meta().fileSize())) {
            throw new IOException("Source file does not match the accepted offer");
        }
        MessageDigest digest = sha256();

        if (session != null) {
            session.startTransfer();
        }

        long totalBytesSent = 0;
        int chunkIndex = 0;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                if (totalBytesSent + bytesRead > fileSize) {
                    throw new IOException("File grew during transfer");
                }
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
        }

        if (totalBytesSent != fileSize) {
            throw new IOException("File size changed during transfer: expected "
                    + fileSize + " bytes, sent " + totalBytesSent + " bytes");
        }

        byte[] hashBytes = digest.digest();
        String checksumHex = HexFormat.of().formatHex(hashBytes);
        if (session != null && !checksumHex.equals(session.meta().checksum())) {
            throw new IOException("Source file changed since the offer was created");
        }

        Packet endPacket = Packet.text(OpCode.FILE_END, chunkIndex, checksumHex);
        PacketCodec.write(output, endPacket);

        return checksumHex;
    }

    public void awaitResult(InputStream input) throws IOException {
        try {
            Packet result = PacketValidator.validateResponse(PacketCodec.read(input), OpCode.FILE_RESULT, 0);
            String status = result.text();
            if (!status.equals("OK")) {
                throw new IOException("Receiver rejected file: " + status);
            }
            if (session != null) {
                session.complete();
            }
        } catch (IOException | RuntimeException e) {
            if (session != null) {
                session.fail();
            }
            throw e;
        }
    }

    public static String checksum(File file) throws IOException {
        validateFile(file);
        long size = file.length();
        long read = 0;
        MessageDigest digest = sha256();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int length;
            while ((length = input.read(buffer)) != -1) {
                read += length;
                if (read > size) {
                    throw new IOException("File grew while computing checksum");
                }
                digest.update(buffer, 0, length);
            }
        }
        if (read != size || file.length() != size) {
            throw new IOException("File size changed while computing checksum");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void validateFile(File file) throws IOException {
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
            throw new IOException("File size exceeds maximum allowed limit: "
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
