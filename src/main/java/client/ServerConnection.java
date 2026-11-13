package client;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import protocols.OpCode;
import protocols.Packet;
import network.PacketChannel;
import network.HeartbeatMonitor;

/** Owns the server reader and serializes correlated management requests. */
final class ServerConnection implements AutoCloseable {
    @FunctionalInterface
    interface EventHandler {
        void accept(Packet packet) throws IOException;
    }

    private static final int RESPONSE_TIMEOUT_SECONDS = 10;
    private record Pending(OpCode opcode, long sequence, CompletableFuture<Packet> future) {}

    private final Runnable onClosed;
    private final PacketChannel channel;
    private final EventHandler events;
    private final HeartbeatMonitor heartbeat;
    private final AtomicBoolean started = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private final Object requestLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Pending pending;

    ServerConnection(PacketChannel channel, EventHandler events) {
        this(channel, events, () -> {});
    }

    ServerConnection(PacketChannel channel, EventHandler events, Runnable onClosed) {
        this.onClosed = Objects.requireNonNull(onClosed);
        this.channel = Objects.requireNonNull(channel);
        this.events = Objects.requireNonNull(events);
        heartbeat = new HeartbeatMonitor(channel, () -> abort("Heartbeat failed", null));
    }

    void start() {
        if (closed.get()) throw new IllegalStateException("Connection closed");
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Reader already started");
        Thread.startVirtualThread(this::readPackets);
        heartbeat.start();
    }

    boolean isOpen() { return !closed.get(); }

    void send(Packet packet) throws IOException {
        if (closed.get()) throw new IOException("Server connection closed");
        try {
            channel.send(packet);
        } catch (IOException e) {
            throw abort("Server send failed", e);
        }
    }

    private void readPackets() {
        try {
            Packet packet;
            while ((packet = channel.read()) != null) {
                if (heartbeat.handle(packet)) continue;
                if (packet.op().requiresStatus()) acceptResponse(packet);
                else events.accept(packet);
            }
        } catch (IOException e) {
            if (!closed.get()) System.err.println("Server receive error: " + e.getMessage());
        } finally {
            try { close(); } catch (IOException e) {
                System.err.println("Cannot close server connection: " + e.getMessage());
            }
        }
    }

    Packet request(Packet packet, OpCode responseOpcode) throws IOException {
        synchronized (requestLock) {
            if (!started.get() || closed.get()) throw new IOException("Server connection is not running");
            CompletableFuture<Packet> response = new CompletableFuture<>();
            try {
                channel.send(packet, sequence -> {
                    synchronized (lifecycleLock) {
                        if (closed.get()) throw new IllegalStateException("Server connection closed");
                        pending = new Pending(responseOpcode, sequence, response);
                    }
                });
                return response.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw abort("Interrupted while waiting for " + responseOpcode, e);
            } catch (TimeoutException e) {
                // Close on timeout so a late reply cannot satisfy a later request.
                throw abort("Timed out waiting for " + responseOpcode, e);
            } catch (ExecutionException | IOException | IllegalStateException e) {
                throw abort("Request failed: " + responseOpcode, e);
            } finally {
                pending = null;
            }
        }
    }

    private void acceptResponse(Packet packet) throws IOException {
        Pending request = pending;
        if (request == null || request.opcode() != packet.op() || request.sequence() != packet.replyTo()
                || !request.future().complete(packet)) {
            throw new IOException("Unexpected response: " + packet.op());
        }
    }

    private IOException abort(String message, Exception cause) {
        IOException failure = new IOException(message, cause);
        try {
            close();
        } catch (IOException closeError) {
            failure.addSuppressed(closeError);
        }
        return failure;
    }

    @Override
    public void close() throws IOException {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
            Pending request = pending;
            if (request != null) request.future().completeExceptionally(new IOException("Server connection closed"));
        }
        // Socket close must not wait for the writer lock: it releases blocked writes.
        heartbeat.close();
        try { channel.close(); } finally { onClosed.run(); }
    }
}
