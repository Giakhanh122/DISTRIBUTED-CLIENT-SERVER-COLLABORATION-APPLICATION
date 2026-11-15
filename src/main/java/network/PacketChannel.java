package network;

import protocols.Packet;
import protocols.PacketCodec;
import protocols.PacketValidator;
import protocols.SystemType;

import java.io.IOException;
import java.io.EOFException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.DataInputStream;
import java.util.function.LongConsumer;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;
import java.util.Arrays;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import security.SessionKeys;

/** Owns the packet streams, sequence counters, and write lock of one TCP socket. */
public final class PacketChannel implements AutoCloseable {
    public enum Role { INITIATOR, RESPONDER }

    private static final int RAW_RECORD_SIZE = 64 * 1024;
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("socket-write-deadlines").factory());
    static { DEADLINES.setRemoveOnCancelPolicy(true); }
    private final Duration writeTimeout;
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final Object readLock = new Object();
    private final Object writeLock = new Object();
    private long receivedSequence;
    private long sentSequence;
    private EncryptedRecords encryptedRecords;
    private byte[] pendingRawBytes = new byte[0];
    private int pendingRawOffset;

    public PacketChannel(Socket socket) throws IOException {
        this(socket, Duration.ofSeconds(30));
    }

    PacketChannel(Socket socket, Duration writeTimeout) throws IOException {
        this.writeTimeout = Objects.requireNonNull(writeTimeout);
        if (writeTimeout.isZero() || writeTimeout.isNegative()) throw new IllegalArgumentException("Invalid timeout");
        this.socket = Objects.requireNonNull(socket);
        this.input = socket.getInputStream();
        this.output = socket.getOutputStream();
    }

    /** Call after HELLO/ACK, before starting the background reader. */
    public void enableEncryption(SessionKeys keys, Role role) throws IOException {
        Objects.requireNonNull(keys);
        Objects.requireNonNull(role);
        synchronized (readLock) {
            synchronized (writeLock) {
                if (encryptedRecords != null) throw new IOException("Encryption already enabled");
                if (sentSequence != 1 || receivedSequence != 1) {
                    throw new IOException("Encryption must be enabled immediately after HELLO/ACK");
                }
                encryptedRecords = role == Role.INITIATOR
                        ? new EncryptedRecords(input, output, keys.initiatorToResponder(), keys.responderToInitiator())
                        : new EncryptedRecords(input, output, keys.responderToInitiator(), keys.initiatorToResponder());
            }
        }
    }

    /** Prove possession of both directional keys before publishing the connection. */
    public void confirmEncryption(SessionKeys keys, Role role) throws IOException {
        try {
            enableEncryption(keys, role);
            if (role == Role.INITIATOR) {
                long sequence = send(Packet.empty(SystemType.KEY_CONFIRM));
                Packet response = PacketValidator.validateResponse(read(), SystemType.KEY_CONFIRM_ACK, sequence);
                PacketValidator.requireSuccess(response);
            } else {
                Packet request = PacketValidator.validateRequest(read(), SystemType.KEY_CONFIRM, 0, 0);
                send(Packet.status(SystemType.KEY_CONFIRM_ACK, request.sequence(), "OK"));
            }
        } catch (IOException e) {
            closeAfterFailure(e);
            throw e;
        }
    }

    public Packet read() throws IOException {
        synchronized (readLock) {
            try {
                Packet packet = encryptedRecords == null ? PacketCodec.read(input) : readEncryptedPacket();
                if (packet == null) return null;
                long expected = nextSequence(receivedSequence);
                if (packet.sequence() != expected) {
                    throw new IOException("Expected sequence " + expected + ", got " + packet.sequence());
                }
                receivedSequence = expected;
                return packet;
            } catch (IOException e) {
                closeAfterFailure(e);
                throw e;
            }
        }
    }

    private Packet readEncryptedPacket() throws IOException {
        if (pendingRawOffset != pendingRawBytes.length) {
            throw new IOException("Unread file data before packet");
        }
        byte[] plaintext = encryptedRecords.read();
        if (plaintext == null) return null;
        ByteArrayInputStream decoded = new ByteArrayInputStream(plaintext);
        Packet packet = PacketCodec.read(decoded);
        if (packet == null || decoded.available() != 0) {
            throw new IOException("Expected exactly one packet in encrypted record");
        }
        return packet;
    }

    public long send(Packet packet) throws IOException {
        return send(packet, sequence -> {});
    }

    public long send(Packet packet, LongConsumer beforeWrite) throws IOException {
        try (WriteDeadline deadline = new WriteDeadline()) {
            synchronized (writeLock) {
                try {
                    long sequence = nextSequence(sentSequence);
                    beforeWrite.accept(sequence);
                    if (encryptedRecords == null) {
                        PacketCodec.write(output, packet, sequence);
                    } else {
                        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                        PacketCodec.write(encoded, packet, sequence);
                        encryptedRecords.write(encoded.toByteArray());
                    }
                    sentSequence = sequence;
                    return sequence;
                } catch (IOException e) {
                    closeAfterFailure(e);
                    throw e;
                }
            }
        }
    }

    public void writeRaw(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        try (WriteDeadline deadline = new WriteDeadline()) {
            synchronized (writeLock) {
                try {
                    if (encryptedRecords == null) {
                        output.write(bytes, offset, length);
                    } else {
                        writeEncryptedRaw(bytes, offset, length);
                    }
                } catch (IOException e) {
                    closeAfterFailure(e);
                    throw e;
                }
            }
        }
    }

    public void readRaw(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        synchronized (readLock) {
            try {
                if (encryptedRecords == null) {
                    new DataInputStream(input).readFully(bytes, offset, length);
                } else {
                    readEncryptedRaw(bytes, offset, length);
                }
            } catch (IOException e) {
                closeAfterFailure(e);
                throw e;
            }
        }
    }

    private void writeEncryptedRaw(byte[] bytes, int offset, int length) throws IOException {
        while (length > 0) {
            int size = Math.min(length, RAW_RECORD_SIZE);
            encryptedRecords.write(Arrays.copyOfRange(bytes, offset, offset + size));
            offset += size;
            length -= size;
        }
    }

    private void readEncryptedRaw(byte[] bytes, int offset, int length) throws IOException {
        while (length > 0) {
            if (pendingRawOffset == pendingRawBytes.length) {
                byte[] record = encryptedRecords.read();
                if (record == null) throw new EOFException("Incomplete encrypted file data");
                if (record.length == 0) throw new IOException("Empty encrypted file data record");
                pendingRawBytes = record;
                pendingRawOffset = 0;
            }
            int size = Math.min(length, pendingRawBytes.length - pendingRawOffset);
            System.arraycopy(pendingRawBytes, pendingRawOffset, bytes, offset, size);
            pendingRawOffset += size;
            offset += size;
            length -= size;
        }
        if (pendingRawOffset == pendingRawBytes.length) {
            pendingRawBytes = new byte[0];
            pendingRawOffset = 0;
        }
    }

    public long lastReceivedSequence() {
        synchronized (readLock) { return receivedSequence; }
    }

    private static long nextSequence(long previous) throws IOException {
        if (previous == Long.MAX_VALUE) throw new IOException("Sequence exhausted; reconnect required");
        return previous + 1;
    }

    private void closeAfterFailure(IOException failure) {
        try {
            close();
        } catch (IOException closeError) {
            failure.addSuppressed(closeError);
        }
    }

    public Socket socket() { return socket; }

    /** SO_TIMEOUT bounds reads only; socket close releases a stalled TCP writer. */
    private final class WriteDeadline implements AutoCloseable {
        private final AtomicBoolean done = new AtomicBoolean();
        private final java.util.concurrent.ScheduledFuture<?> timer = DEADLINES.schedule(() -> {
            if (done.compareAndSet(false, true)) {
                try { socket.close(); } catch (IOException e) {
                    System.err.println("Cannot close stalled socket: " + e.getMessage());
                }
            }
        }, writeTimeout.toNanos(), TimeUnit.NANOSECONDS);
        public void close() { done.set(true); timer.cancel(false); }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
