package network;

import protocols.OpCode;
import protocols.Packet;
import protocols.PacketValidator;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Liveness of one authenticated control socket, independent of management requests. */
public final class HeartbeatMonitor implements AutoCloseable {
    public static final Duration INTERVAL = Duration.ofSeconds(5);
    public static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(2,
            Thread.ofPlatform().daemon().name("heartbeat-timer-", 0).factory());
    static { TIMER.setRemoveOnCancelPolicy(true); }

    private final PacketChannel channel;
    private final Runnable onFailure;
    private final long intervalNanos;
    private final long timeoutNanos;
    private ScheduledFuture<?> ticker;
    private boolean closed;
    private boolean awaiting;
    private long sentAt;
    private long sequence;

    public HeartbeatMonitor(PacketChannel channel, Runnable onFailure) {
        this(channel, onFailure, INTERVAL, TIMEOUT);
    }

    public HeartbeatMonitor(PacketChannel channel, Runnable onFailure, Duration interval, Duration timeout) {
        this.channel = Objects.requireNonNull(channel);
        this.onFailure = Objects.requireNonNull(onFailure);
        intervalNanos = interval.toNanos();
        timeoutNanos = timeout.toNanos();
        if (intervalNanos <= 0 || timeoutNanos < intervalNanos) throw new IllegalArgumentException("Invalid heartbeat timing");
    }

    public synchronized void start() {
        if (closed) return;
        if (ticker != null) throw new IllegalStateException("Heartbeat already started");
        ticker = TIMER.scheduleWithFixedDelay(this::tick, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS);
    }

    private void tick() {
        boolean expired;
        synchronized (this) {
            if (closed) return;
            expired = awaiting && System.nanoTime() - sentAt >= timeoutNanos;
            if (!expired) {
                if (awaiting) return;
                awaiting = true;
                sentAt = System.nanoTime();
                sequence = 0;
            }
        }
        if (expired) fail();
        else Thread.startVirtualThread(() -> {
            try {
                channel.send(Packet.empty(OpCode.HEARTBEAT), value -> {
                    synchronized (this) { sequence = value; }
                });
            } catch (IOException | RuntimeException e) { fail(); }
        });
    }

    /** Called only by the socket's existing reader; never creates a second reader. */
    public boolean handle(Packet packet) throws IOException {
        if (packet.op() == OpCode.HEARTBEAT) {
            PacketValidator.validateRequest(packet, OpCode.HEARTBEAT, 0, 0);
            channel.send(Packet.status(OpCode.HEARTBEAT_ACK, packet.sequence(), "OK"));
            return true;
        }
        if (packet.op() != OpCode.HEARTBEAT_ACK) return false;
        PacketValidator.requireSuccess(packet);
        synchronized (this) {
            if (closed) return true;
            if (!awaiting || sequence == 0 || packet.replyTo() != sequence) {
                throw new IOException("Unexpected heartbeat acknowledgement");
            }
            awaiting = false;
        }
        return true;
    }

    private void fail() {
        synchronized (this) {
            if (closed) return;
            close();
        }
        // Timeout callbacks must not block the shared timer on a listener or socket write.
        Thread.startVirtualThread(onFailure);
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (ticker != null) ticker.cancel(false);
    }
}
