package transfer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Path tempFile = null;
        try {
            Path directory = saveDirectory.toPath();
            Files.createDirectories(directory);
            String safeName = sanitizeFileName(meta.fileName());
            // Never use untrusted metadata as the temporary file's path.
            tempFile = Files.createTempFile(directory, ".transfer-", ".tmp");
            MessageDigest digest = sha256();
            if (session != null) {
                synchronized (session) {
                    if (session.state() == FileTransferSession.State.ACCEPTED) {
                        session.startTransfer();
                    } else if (session.state() != FileTransferSession.State.TRANSFERRING) {
                        throw new IOException("File session is not ready to receive");
                    }
                }
            }

            long received = 0;
            int expectedIndex = 0;
            String remoteChecksum;
            try (OutputStream fileOutput = Files.newOutputStream(tempFile)) {
                while (true) {
                    Packet packet = PacketCodec.read(input);
                    if (packet == null) {
                        throw new IOException("Connection closed prematurely by sender");
                    }
                    if (packet.op() == OpCode.FILE_CHUNK) {
                        if (packet.requestId() != expectedIndex) {
                            throw new IOException("Out of order chunk: expected " + expectedIndex
                                    + ", got " + packet.requestId());
                        }
                        int length = packet.payloadLength();
                        if (length <= 0 || length > FileSender.CHUNK_SIZE) {
                            throw new IOException("Invalid file chunk length: " + length);
                        }
                        if (received + length > meta.fileSize()) {
                            throw new IOException("Received bytes exceed offered file size");
                        }
                        byte[] data = packet.data();
                        fileOutput.write(data);
                        digest.update(data);
                        received += length;
                        expectedIndex++;
                        if (session != null) {
                            session.addBytesTransferred(length);
                        }
                        if (listener != null) {
                            listener.onProgress(received, meta.fileSize(),
                                    (int) (received * 100 / meta.fileSize()));
                        }
                    } else if (packet.op() == OpCode.FILE_END) {
                        if (packet.requestId() != expectedIndex) {
                            throw new IOException("FILE_END chunk count mismatch");
                        }
                        remoteChecksum = new String(packet.data(), StandardCharsets.UTF_8);
                        break;
                    } else {
                        throw new IOException("Unexpected file packet: " + packet.op());
                    }
                }
            }

            if (received != meta.fileSize()) {
                throw new IOException("File size mismatch: expected " + meta.fileSize()
                        + ", received " + received);
            }
            String checksum = HexFormat.of().formatHex(digest.digest());
            if (!checksum.equals(meta.checksum()) || !checksum.equalsIgnoreCase(remoteChecksum)) {
                throw new IOException("Checksum mismatch");
            }

            Path target = publishFile(tempFile, directory, safeName);
            tempFile = null;
            sendResult("OK");
            if (session != null) {
                session.complete();
            }
            return target.toFile();
        } catch (IOException | RuntimeException e) {
            if (session != null) {
                session.fail();
            }
            try {
                sendResult("FAIL: " + e.getMessage());
            } catch (IOException resultError) {
                e.addSuppressed(resultError);
            }
            throw e;
        } finally {
            if (tempFile != null) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String sanitizeFileName(String rawName) {
        String normalized = rawName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "downloaded_" + meta.transferId().substring(0, 8);
        }
        return name;
    }

    private Path publishFile(Path temp, Path directory, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int suffix = 0; ; suffix++) {
            Path target = directory.resolve(suffix == 0 ? name : base + " (" + suffix + ")" + extension);
            try {
                // Reserve a distinct destination even when downloads finish at the same time.
                Files.createFile(target);
            } catch (FileAlreadyExistsException e) {
                continue;
            }
            boolean moved = false;
            try {
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                moved = true;
                return target;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(target);
                }
            }
        }
    }

    private void sendResult(String status) throws IOException {
        if (output != null) {
            PacketCodec.write(output, new Packet(OpCode.FILE_RESULT, 0,
                    status.getBytes(StandardCharsets.UTF_8)));
        }
    }

    public FileTransferMeta getMeta() {
        return meta;
    }
}
