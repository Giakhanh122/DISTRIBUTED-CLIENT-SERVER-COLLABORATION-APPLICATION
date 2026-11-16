package network;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import protocols.Json;
import protocols.Packet;
import protocols.PacketCodec;
import protocols.type.ChatType;
import protocols.type.SystemType;
import security.SessionKeys;

@Timeout(10)
class TransportRobustnessTest {
    @Test
    void fragmentedAndCoalescedFramesPreserveMessageBoundaries() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PacketCodec.write(bytes, Packet.json(ChatType.MESSAGE, Json.object("content", "first")), 1);
        PacketCodec.write(bytes, Packet.json(ChatType.MESSAGE, Json.object("content", "second")), 2);
        var input = new ByteArrayInputStream(bytes.toByteArray()) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(1, len));
            }
        };
        assertEquals("first", PacketCodec.read(input).string("content"));
        assertEquals("second", PacketCodec.read(input).string("content"));
        assertNull(PacketCodec.read(input));
    }

    @Test
    void authenticatedEncryptionRejectsTamperingAndTruncatedRecords() throws Exception {
        SessionKeys keys = SessionKeys.derive(new byte[32], new byte[]{1, 2, 3});
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new EncryptedRecords(new ByteArrayInputStream(new byte[0]), bytes,
                keys.initiatorToResponder(), keys.responderToInitiator()).write(new byte[]{42, 10, 20});
        byte[] valid = bytes.toByteArray();
        assertArrayEquals(new byte[]{42, 10, 20}, new EncryptedRecords(new ByteArrayInputStream(valid),
                new ByteArrayOutputStream(), keys.responderToInitiator(), keys.initiatorToResponder()).read());
        byte[] modified = valid.clone();
        modified[modified.length - 1] ^= 1;
        assertThrows(IOException.class, () -> new EncryptedRecords(new ByteArrayInputStream(modified),
                new ByteArrayOutputStream(), keys.responderToInitiator(), keys.initiatorToResponder()).read());
        byte[] truncated = Arrays.copyOf(valid, valid.length - 2);
        assertThrows(IOException.class, () -> new EncryptedRecords(new ByteArrayInputStream(truncated),
                new ByteArrayOutputStream(), keys.responderToInitiator(), keys.initiatorToResponder()).read());
    }

    @Test
    void heartbeatDetectsSilentConnectionAndAcceptsMatchingPong() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                var remote = new PacketChannel(listener.accept()); var local = new PacketChannel(socket)) {
            socket.setSoTimeout(2000); remote.socket().setSoTimeout(2000);
            CountDownLatch failure = new CountDownLatch(1);
            try (var monitor = new HeartbeatMonitor(local, failure::countDown,
                    Duration.ofMillis(25), Duration.ofMillis(200))) {
                monitor.start();
                Packet first = remote.read();
                remote.send(Packet.status(SystemType.PONG, first.sequence(), "OK"));
                assertTrue(monitor.handle(local.read()));
                assertEquals(SystemType.PING, remote.read().type());
                assertTrue(failure.await(2, TimeUnit.SECONDS));
            }
        }
    }
}
