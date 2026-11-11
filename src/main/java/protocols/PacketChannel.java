package protocols;

import java.io.IOException;
import java.io.InputStream;
import java.io.DataInputStream;
import java.util.function.LongConsumer;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;

/** Owns the packet streams, sequence counters, and write lock of one TCP socket. */
public final class PacketChannel implements AutoCloseable {
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final Object readLock = new Object();
    private final Object writeLock = new Object();
    private long receivedSequence;
    private long sentSequence;

    public PacketChannel(Socket socket) throws IOException {
        this.socket = Objects.requireNonNull(socket);
        this.input = socket.getInputStream();
        this.output = socket.getOutputStream();
    }

    public Packet read() throws IOException {
        synchronized (readLock) {
            try {
                Packet packet = PacketCodec.read(input);
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

    public long send(Packet packet) throws IOException {
        return send(packet, sequence -> {});
    }

    public long send(Packet packet, LongConsumer beforeWrite) throws IOException {
        synchronized (writeLock) {
            try {
                long sequence = nextSequence(sentSequence);
                beforeWrite.accept(sequence);
                PacketCodec.write(output, packet, sequence);
                sentSequence = sequence;
                return sequence;
            } catch (IOException e) {
                closeAfterFailure(e);
                throw e;
            }
        }
    }

    public void writeRaw(byte[] bytes, int offset, int length) throws IOException {
        synchronized (writeLock) {
            try {
                output.write(bytes, offset, length);
            } catch (IOException e) {
                closeAfterFailure(e);
                throw e;
            }
        }
    }

    public void readRaw(byte[] bytes, int offset, int length) throws IOException {
        synchronized (readLock) {
            try {
                new DataInputStream(input).readFully(bytes, offset, length);
            } catch (IOException e) {
                closeAfterFailure(e);
                throw e;
            }
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

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
