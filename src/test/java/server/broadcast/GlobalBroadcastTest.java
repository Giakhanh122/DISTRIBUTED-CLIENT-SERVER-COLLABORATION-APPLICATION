package server.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import network.PacketChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protocols.Json;
import protocols.Packet;
import protocols.type.ChatType;
import server.community.CommunityRegistry;
import server.community.CommunityService;
import server.session.UserSession;
import server.session.UserSessionRegistry;
import server.storage.FileStorageService;

class GlobalBroadcastTest {
    @Test
    void rejectsContentOverUtf8ByteLimit(@TempDir Path tempDir) throws Exception {
        UserSessionRegistry users = new UserSessionRegistry();
        UserSession sender = new UserSession("alice", "127.0.0.1", 9001, null);
        assertTrue(users.register(sender));
        BroadcastService service = new BroadcastService(users,
                new CommunityService(new CommunityRegistry(), users),
                new BroadcastFileRegistry(), new FileStorageService(tempDir.resolve("storage")));
        String oversized = "é".repeat(32_769);
        Packet request = Packet.json(ChatType.GLOBAL_BROADCAST, Json.object("content", oversized));

        assertThrows(IOException.class, () -> service.handleGlobalBroadcastMessage(request, sender));
    }

    @Test
    void sendsToEveryOnlineUserExceptSenderAndUsesAuthenticatedIdentity(@TempDir Path tempDir) throws Exception {
        try (ChannelPair alice = connect(); ChannelPair bob = connect(); ChannelPair carol = connect()) {
            UserSessionRegistry users = new UserSessionRegistry();
            UserSession aliceSession = new UserSession("alice", "127.0.0.1", 9001, alice.server());
            assertTrue(users.register(aliceSession));
            aliceSession.activate();
            UserSession bobSession = new UserSession("bob", "127.0.0.1", 9002, bob.server());
            UserSession carolSession = new UserSession("carol", "127.0.0.1", 9003, carol.server());
            assertTrue(users.register(bobSession));
            assertTrue(users.register(carolSession));
            bobSession.activate();
            carolSession.activate();

            BroadcastService service = new BroadcastService(users,
                    new CommunityService(new CommunityRegistry(), users),
                    new BroadcastFileRegistry(), new FileStorageService(tempDir.resolve("storage")));
            Packet request = Packet.json(ChatType.GLOBAL_BROADCAST,
                    Json.object("sender", "spoofed", "content", "hello everyone"));

            service.handleGlobalBroadcastMessage(request, aliceSession);

            for (Packet received : List.of(bob.client().read(), carol.client().read())) {
                assertEquals(ChatType.GLOBAL_BROADCAST, received.type());
                assertEquals("alice", received.string("sender"));
                assertEquals("hello everyone", received.string("content"));
            }
            assertEquals(0, alice.client().socket().getInputStream().available());
        }
    }

    private static ChannelPair connect() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket listener = new ServerSocket(0, 1, loopback)) {
            Socket clientSocket = new Socket(loopback, listener.getLocalPort());
            Socket serverSocket = listener.accept();
            clientSocket.setSoTimeout(2000);
            serverSocket.setSoTimeout(2000);
            return new ChannelPair(new PacketChannel(serverSocket), new PacketChannel(clientSocket));
        }
    }

    private record ChannelPair(PacketChannel server, PacketChannel client) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                server.close();
            } catch (IOException e) {
                failure = e;
            }
            try {
                client.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
            if (failure != null) throw failure;
        }
    }
}
