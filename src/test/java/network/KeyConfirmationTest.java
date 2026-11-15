package network;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import protocols.*;
import security.*;

class KeyConfirmationTest {
    private static final class Pair implements AutoCloseable {
        final PacketChannel initiator;
        final PacketChannel responder;
        final SessionKeys keys;

        Pair() throws Exception {
            try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                Socket server = listener.accept();
                client.setSoTimeout(2000);
                server.setSoTimeout(2000);
                initiator = new PacketChannel(client);
                responder = new PacketChannel(server);
            }
            var a = X25519.createKeyPair();
            var b = X25519.createKeyPair();
            keys = X25519.deriveSessionKeys(a, b.getPublic(), new byte[]{1});
            long sequence = initiator.send(Packet.json(ConnectionType.HELLO, Json.object("protocolVersion", 2)));
            responder.read();
            responder.send(Packet.reply(ConnectionType.HELLO_ACK, sequence,
                    Json.object("status", "OK", "protocolVersion", 2)));
            initiator.read();
        }

        public void close() throws IOException {
            try { initiator.close(); } finally { responder.close(); }
        }
    }

    @Test void confirmsBothDirectionsBeforeApplicationPackets() throws Exception {
        try (var pair = new Pair(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> confirmed = executor.submit(() -> {
                pair.responder.confirmEncryption(pair.keys, PacketChannel.Role.RESPONDER);
                return null;
            });
            pair.initiator.confirmEncryption(pair.keys, PacketChannel.Role.INITIATOR);
            confirmed.get(3, TimeUnit.SECONDS);
            long sequence = pair.initiator.send(Packet.json(AuthType.LOGIN_REQUEST,
                    Json.object("username", "alice", "password", "secret", "peerPort", 9000)));
            assertEquals(3, sequence);
            assertEquals(AuthType.LOGIN_REQUEST, pair.responder.read().type());
            pair.responder.send(Packet.status(AuthType.LOGIN_RESPONSE, sequence, "OK"));
            PacketValidator.validateResponse(pair.initiator.read(), AuthType.LOGIN_RESPONSE, sequence);
        }
    }

    @Test void wrongForwardKeyFailsAndClosesBothSockets() throws Exception {
        try (var pair = new Pair(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SessionKeys wrong = SessionKeys.derive(new byte[32], new byte[]{2});
            Future<?> rejected = executor.submit(() -> {
                assertThrows(IOException.class,
                        () -> pair.responder.confirmEncryption(wrong, PacketChannel.Role.RESPONDER));
                assertTrue(pair.responder.socket().isClosed());
                return null;
            });
            assertThrows(IOException.class,
                    () -> pair.initiator.confirmEncryption(pair.keys, PacketChannel.Role.INITIATOR));
            assertTrue(pair.initiator.socket().isClosed());
            rejected.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void rejectsDifferentSystemResponseEvenWithMatchingReplyTo() throws Exception {
        try (var pair = new Pair(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> peer = executor.submit(() -> {
                pair.responder.enableEncryption(pair.keys, PacketChannel.Role.RESPONDER);
                Packet request = pair.responder.read();
                pair.responder.send(Packet.status(SystemType.PONG, request.sequence(), "OK"));
                return null;
            });
            assertThrows(IOException.class,
                    () -> pair.initiator.confirmEncryption(pair.keys, PacketChannel.Role.INITIATOR));
            assertTrue(pair.initiator.socket().isClosed());
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void wrongReverseKeyRejectsAcknowledgement() throws Exception {
        try (var pair = new Pair(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SessionKeys other = SessionKeys.derive(new byte[32], new byte[]{3});
            SessionKeys wrongReverse = new SessionKeys(pair.keys.initiatorToResponder(),
                    other.responderToInitiator());
            Future<?> peer = executor.submit(() -> {
                pair.responder.confirmEncryption(wrongReverse, PacketChannel.Role.RESPONDER);
                return null;
            });
            assertThrows(IOException.class,
                    () -> pair.initiator.confirmEncryption(pair.keys, PacketChannel.Role.INITIATOR));
            assertTrue(pair.initiator.socket().isClosed());
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void pingCannotReplaceKeyConfirmationRequest() throws Exception {
        try (var pair = new Pair(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> peer = executor.submit(() -> {
                assertThrows(IOException.class,
                        () -> pair.responder.confirmEncryption(pair.keys, PacketChannel.Role.RESPONDER));
                assertTrue(pair.responder.socket().isClosed());
                return null;
            });
            pair.initiator.enableEncryption(pair.keys, PacketChannel.Role.INITIATOR);
            pair.initiator.send(Packet.empty(SystemType.PING));
            peer.get(3, TimeUnit.SECONDS);
        }
    }
}
