package transfer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import protocols.FileTransferMeta;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;

public class FileReceiver {

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long bytesReceived, long totalBytes, int percent);
    }

    private final InputStream input;
    private final OutputStream output;
    private final File saveDirectory;
    private final FileTransferMeta meta;
    private final FileTransferSession session;
    private final ProgressListener listener;

    public FileReceiver(InputStream input, OutputStream output, File saveDirectory,
                        FileTransferMeta meta) {
        this(input, output, saveDirectory, meta, null, null);
    }

    public FileReceiver(InputStream input, OutputStream output, File saveDirectory,
                        FileTransferMeta meta, FileTransferSession session,
                        ProgressListener listener) {
        this.input = Objects.requireNonNull(input, "input must not be null");
        this.output = output;
        this.saveDirectory = Objects.requireNonNull(saveDirectory, "saveDirectory must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
        this.session = session;
        this.listener = listener;
    }

    public File receive() throws IOException {
        validateDirectory();

        String safeFileName = sanitizeFileName(meta.fileName());
        File tempFile = new File(saveDirectory, "." + meta.transferId() + ".tmp");

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available in JVM", e);
        }

        if (session != null && session.state() == FileTransferSession.State.ACCEPTED) {
            session.startTransfer();
        }

        long totalBytesReceived = 0;
        int expectedChunkIndex = 0;
        String remoteChecksum = null;

        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            while (true) {
                Packet packet = PacketCodec.read(input);
                if (packet == null) {
                    throw new IOException("Connection closed prematurely by sender");
                }

                if (packet.op() == OpCode.FILE_CHUNK) {
                    if (packet.requestId() != expectedChunkIndex) {
                        throw new IOException("Out of order chunk: expected "
                                + expectedChunkIndex + ", got " + packet.requestId());
                    }

                    int chunkLen = packet.payloadLength();
                    if (totalBytesReceived + chunkLen > meta.fileSize()) {
                        throw new IOException("Received bytes (" + (totalBytesReceived + chunkLen)
                                + ") exceeds expected file size (" + meta.fileSize() + ")");
                    }

                    byte[] data = packet.data();
                    fos.write(data);
                    digest.update(data);

                    totalBytesReceived += chunkLen;
                    expectedChunkIndex++;

                    if (session != null) {
                        session.addBytesTransferred(chunkLen);
                    }
                    if (listener != null) {
                        int percent = meta.fileSize() > 0
                                ? (int) (totalBytesReceived * 100 / meta.fileSize())
                                : 100;
                        listener.onProgress(totalBytesReceived, meta.fileSize(), percent);
                    }
                } else if (packet.op() == OpCode.FILE_END) {
                    remoteChecksum = new String(packet.data(), StandardCharsets.UTF_8);
                    break;
                } else if (packet.op() == OpCode.FILE_CANCEL) {
                    throw new IOException("Transfer canceled by sender");
                } else {
                    throw new IOException("Unexpected packet opcode: " + packet.op());
                }
            }
        } catch (Exception e) {
            cleanupTempFile(tempFile);
            if (session != null && !session.isTerminal()) {
                session.fail();
            }
            sendResultSafe("FAIL: " + e.getMessage());
            throw e;
        }

        if (totalBytesReceived != meta.fileSize()) {
            cleanupTempFile(tempFile);
            if (session != null && !session.isTerminal()) {
                session.fail();
            }
            String errMsg = "File size mismatch: expected " + meta.fileSize()
                    + ", received " + totalBytesReceived;
            sendResultSafe("FAIL: " + errMsg);
            throw new IOException(errMsg);
        }

        String localChecksum = HexFormat.of().formatHex(digest.digest());
        if (!localChecksum.equalsIgnoreCase(remoteChecksum)) {
            cleanupTempFile(tempFile);
            if (session != null && !session.isTerminal()) {
                session.fail();
            }
            String errMsg = "Checksum mismatch: expected " + remoteChecksum
                    + ", calculated " + localChecksum;
            sendResultSafe("FAIL: " + errMsg);
            throw new IOException(errMsg);
        }

        File targetFile = resolveUniqueFile(saveDirectory, safeFileName);
        try {
            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        sendResultSafe("OK");

        if (session != null && session.state() == FileTransferSession.State.TRANSFERRING) {
            session.complete();
        }

        return targetFile;
    }

    private void validateDirectory() throws IOException {
        if (!saveDirectory.exists() && !saveDirectory.mkdirs()) {
            throw new IOException("Failed to create save directory: "
                    + saveDirectory.getAbsolutePath());
        }
        if (!saveDirectory.isDirectory()) {
            throw new IOException("Save path is not a directory: "
                    + saveDirectory.getAbsolutePath());
        }
        if (!saveDirectory.canWrite()) {
            throw new IOException("Cannot write to directory: "
                    + saveDirectory.getAbsolutePath());
        }
    }

    private String sanitizeFileName(String rawName) {
        String name = Paths.get(rawName).getFileName().toString();
        name = name.replace("\\", "").replace("/", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "downloaded_" + meta.transferId().substring(0, 8);
        }
        return name;
    }

    private File resolveUniqueFile(File dir, String fileName) {
        File file = new File(dir, fileName);
        if (!file.exists()) {
            return file;
        }

        String baseName;
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        } else {
            baseName = fileName;
        }

        int count = 1;
        while (file.exists()) {
            file = new File(dir, baseName + " (" + count + ")" + extension);
            count++;
        }
        return file;
    }

    private void cleanupTempFile(File tempFile) {
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }

    private void sendResultSafe(String status) {
        if (output == null) {
            return;
        }
        try {
            byte[] data = status.getBytes(StandardCharsets.UTF_8);
            Packet resultPacket = new Packet(OpCode.FILE_RESULT, 0, data);
            PacketCodec.write(output, resultPacket);
        } catch (IOException ignored) {
        }
    }

    public FileTransferMeta getMeta() {
        return meta;
    }
}
