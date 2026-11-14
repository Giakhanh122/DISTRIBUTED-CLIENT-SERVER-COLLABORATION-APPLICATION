package server.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public final class ServerIdentity {
    private static final String ALGORITHM = "Ed25519";
    private static final String PRIVATE_FILE = "server-private.key";
    private static final String PUBLIC_FILE = "server-public.key";
    private final KeyPair keyPair;

    private ServerIdentity(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static ServerIdentity generate() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
        return new ServerIdentity(generator.generateKeyPair());
    }

    public byte[] sign(byte[] message) throws GeneralSecurityException {
        Signature signer = Signature.getInstance(ALGORITHM);
        signer.initSign(keyPair.getPrivate());
        signer.update(message);
        return signer.sign();
    }

    public void save(Path directory) throws IOException {
        Path target = normalizeDirectory(directory);
        synchronized (ServerIdentity.class) {
            try (FileChannel lockChannel = openLock(target); FileLock lock = lockChannel.lock()) {
                saveLocked(target);
            }
        }
    }

    public static ServerIdentity load(Path directory) throws IOException, GeneralSecurityException {
        KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                Files.readAllBytes(directory.resolve(PRIVATE_FILE))));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
                Files.readAllBytes(directory.resolve(PUBLIC_FILE))));
        validateKeyPair(privateKey, publicKey);
        return new ServerIdentity(new KeyPair(publicKey, privateKey));
    }

    public static ServerIdentity loadOrCreate(Path directory) throws IOException, GeneralSecurityException {
        Path target = normalizeDirectory(directory);
        synchronized (ServerIdentity.class) {
            try (FileChannel lockChannel = openLock(target); FileLock lock = lockChannel.lock()) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    requireDirectory(target);
                    if (!isEmpty(target)) return load(target);
                }
                ServerIdentity identity = generate();
                identity.saveLocked(target);
                return identity;
            }
        }
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    private static void validateKeyPair(PrivateKey privateKey, PublicKey publicKey)
            throws GeneralSecurityException {
        byte[] challenge = new byte[32];
        new SecureRandom().nextBytes(challenge);
        Signature signer = Signature.getInstance(ALGORITHM);
        signer.initSign(privateKey);
        signer.update(challenge);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance(ALGORITHM);
        verifier.initVerify(publicKey);
        verifier.update(challenge);
        if (!verifier.verify(signature)) {
            throw new GeneralSecurityException("Public/private key mismatch");
        }
    }

    private void saveLocked(Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectory(target);
            if (!isEmpty(target)) throw new IOException("Identity directory is not empty: " + target);
        }

        Path staging = Files.createTempDirectory(target.getParent(), "." + target.getFileName() + "-",
                permissions(target.getParent(), "rwx------"));
        try {
            writeKey(staging.resolve(PRIVATE_FILE), keyPair.getPrivate().getEncoded(), "rw-------");
            writeKey(staging.resolve(PUBLIC_FILE), keyPair.getPublic().getEncoded(), "rw-r--r--");
            
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.delete(target);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            cleanupStaging(staging, e);
            throw e;
        }
    }

    private static void writeKey(Path path, byte[] encoded, String mode) throws IOException {
        Files.createFile(path, permissions(path.getParent(), mode));
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static FileAttribute<?>[] permissions(Path directory, String mode) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(mode))};
        }
        return new FileAttribute<?>[0];
    }

    private static Path normalizeDirectory(Path directory) throws IOException {
        Path target = directory.toAbsolutePath().normalize();
        if (target.getParent() == null) throw new IOException("Identity directory cannot be a filesystem root");
        return target;
    }

    private static FileChannel openLock(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path lockPath = target.resolveSibling("." + target.getFileName() + ".lock");
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    private static void requireDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Identity path must be a directory, not a file or symbolic link: " + path);
        }
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.newDirectoryStream(directory)) {
            return !entries.iterator().hasNext();
        }
    }

    private static void cleanupStaging(Path staging, Exception failure) {
        for (Path path : new Path[]{staging.resolve(PRIVATE_FILE), staging.resolve(PUBLIC_FILE), staging}) {
            try { Files.deleteIfExists(path); }
            catch (IOException cleanupError) { failure.addSuppressed(cleanupError); }
        }
    }
}
