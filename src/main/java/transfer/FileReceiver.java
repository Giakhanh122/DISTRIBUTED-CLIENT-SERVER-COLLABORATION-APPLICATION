package transfer;

import protocols.type.FileType;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import protocols.FileTransferMeta;
import protocols.Packet;
import network.PacketChannel;

public class FileReceiver {
    public static final class CleanupException extends IOException {
        public CleanupException(Path path, IOException cause) {
            super("Unable to remove partial file: " + path, cause);
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long bytesReceived, long totalBytes, int percent);
    }

    private final PacketChannel channel;
    private final File saveDirectory;
    private final FileTransferMeta meta;
    private final FileTransferSession session;
    private final ProgressListener listener;

    public FileReceiver(PacketChannel channel, File saveDirectory,
                        FileTransferMeta meta) {
        this(channel, saveDirectory, meta, null, null);
    }

    public FileReceiver(PacketChannel channel, File saveDirectory,
                        FileTransferMeta meta, FileTransferSession session,
                        ProgressListener listener) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.saveDirectory = Objects.requireNonNull(saveDirectory, "saveDirectory must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
        this.session = session;
        this.listener = listener;
    }

    public File receive() throws IOException {
        Path tempFile = null;
        Exception failure = null;
        try {
            if (session != null) {
                synchronized (session) {
                    if (session.state() == FileTransferSession.State.ACCEPTED) {
                        session.startTransfer();
                    } else if (session.state() != FileTransferSession.State.TRANSFERRING) {
                        throw new IOException("File session is not ready to receive");
                    }
                }
            }
            Path directory = saveDirectory.toPath();
            Files.createDirectories(directory);
            String safeName = sanitizeFileName(meta.fileName());
            // Never use untrusted metadata as the temporary file's path.
            tempFile = Files.createTempFile(directory, ".transfer-", ".tmp");

            if (listener != null) listener.onProgress(0, meta.fileSize(), meta.fileSize() == 0 ? 100 : 0);
            try (OutputStream fileOutput = Files.newOutputStream(tempFile)) {
                FileDataReader.receive(channel, fileOutput, meta.transferId(), meta.fileSize(), meta.checksum(),
                        (length, received) -> {
                            if (session != null) session.addBytesTransferred(length);
                            if (listener != null) {
                                listener.onProgress(received, meta.fileSize(), (int) (received * 100 / meta.fileSize()));
                            }
                        });
            }

            Path target;
            if (session == null) target = publishFile(tempFile, directory, safeName);
            else {
                synchronized (session) {
                    if (session.state() != FileTransferSession.State.TRANSFERRING) {
                        throw new IOException("Transfer canceled before publishing");
                    }
                    target = publishFile(tempFile, directory, safeName);
                    session.complete();
                }
            }
            tempFile = null;
            try {
                channel.send(Packet.status(FileType.FILE_RESULT, channel.lastReceivedSequence(), "OK"));
            } catch (IOException e) {
                // Local save is committed. Sender still needs this ACK to confirm delivery.
                System.err.println("File saved; confirmation failed: " + e.getMessage());
            }
            return target.toFile();
        } catch (IOException | RuntimeException e) {
            failure = e;
            if (session != null) {
                session.fail();
            }
            try {
                channel.send(Packet.failure(channel.lastReceivedSequence(), e.getMessage()));
            } catch (IOException resultError) {
                e.addSuppressed(resultError);
            }
            throw e;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException cleanupError) {
                    CleanupException cleanupFailure = new CleanupException(tempFile, cleanupError);
                    if (failure != null) cleanupFailure.addSuppressed(failure);
                    throw cleanupFailure;
                }
            }
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
            try {
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return target;
            } catch (IOException | RuntimeException e) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupError) {
                    e.addSuppressed(cleanupError);
                }
                throw e;
            }
        }
    }

    public FileTransferMeta getMeta() {
        return meta;
    }
}
