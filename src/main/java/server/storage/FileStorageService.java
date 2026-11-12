package server.storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Manages server-side file storage for broadcast file transfers.
 * Files are streamed in chunks to avoid loading entire files into memory.
 */
public class FileStorageService {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final Path storageDir;

    public FileStorageService(Path storageDir) {
        this.storageDir = storageDir;
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create storage directory: " + storageDir, e);
        }
    }

    /** Get the path for a temporary upload file. */
    public Path tempPath(String fileId) {
        return storageDir.resolve(".upload-" + fileId + ".tmp");
    }

    /** Get the path for a committed file. */
    public Path committedPath(String fileId, String fileName) {
        String safeName = sanitize(fileName);
        return storageDir.resolve(fileId + "_" + safeName);
    }

    /** Open an output stream for writing a temp upload file. */
    public OutputStream openTempForWrite(String fileId) throws IOException {
        Path temp = tempPath(fileId);
        return new FileOutputStream(temp.toFile());
    }

    /** Atomically commit a temp file after verification. */
    public Path commit(String fileId, String fileName) throws IOException {
        Path temp = tempPath(fileId);
        Path target = committedPath(fileId, fileName);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /** Delete a temp file (cleanup on failure). */
    public void deleteTempIfExists(String fileId) {
        try {
            Files.deleteIfExists(tempPath(fileId));
        } catch (IOException ignored) {}
    }

    /** Open a committed file for reading (for downloads). */
    public InputStream openForRead(String fileId, String fileName) throws IOException {
        Path path = committedPath(fileId, fileName);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + fileId);
        }
        return new FileInputStream(path.toFile());
    }

    /** Get the size of a committed file. */
    public long fileSize(String fileId, String fileName) throws IOException {
        return Files.size(committedPath(fileId, fileName));
    }

    /** Compute SHA-256 of a file on disk. */
    public static String computeChecksum(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        try (InputStream in = new FileInputStream(file.toFile())) {
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = in.read(buf)) != -1) {
                digest.update(buf, 0, len);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sanitize(String rawName) {
        String normalized = rawName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "file";
        }
        // Remove characters problematic for filesystems
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
