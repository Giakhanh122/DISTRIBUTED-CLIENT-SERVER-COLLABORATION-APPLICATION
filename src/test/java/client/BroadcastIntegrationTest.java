package client;

import static org.junit.jupiter.api.Assertions.*;

import client.security.ServerKeyPinning;
import client.transfer.FileOffer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import server.Server;
import server.security.ServerIdentity;

@Timeout(30)
class BroadcastIntegrationTest {
    @TempDir Path root;

    @Test
    void serverRoutesGlobalAndCommunityMessagesWithoutOpeningPeerConnections() throws Exception {
        try (Fixture f = new Fixture(root)) {
            Events a = new Events(), b = new Events(), c = new Events();
            ClientSession alice = f.login("alice", a), bob = f.login("bob", b), carol = f.login("admin1", c);
            var first = alice.communities().create("First");
            bob.communities().join(first.id());
            var second = bob.communities().create("Second");
            alice.communities().join(second.id());
            alice.broadcastMessage(first.id(), "community only");
            assertEquals(first.id() + ":alice:community only", b.group.poll(5, TimeUnit.SECONDS));
            assertEquals(second.id(), bob.communities().selected().id());
            alice.broadcastMessage(second.id(), "second group");
            assertEquals(second.id() + ":alice:second group", b.group.poll(5, TimeUnit.SECONDS));
            alice.broadcastGlobalMessage("everyone");
            assertEquals("alice:everyone", b.global.poll(5, TimeUnit.SECONDS));
            assertEquals("alice:everyone", c.global.poll(5, TimeUnit.SECONDS));
            assertTrue(a.group.isEmpty()); assertTrue(a.global.isEmpty());
            assertTrue(c.group.isEmpty()); assertTrue(b.direct.isEmpty());
            assertTrue(alice.connectedPeers().isEmpty());
            assertTrue(bob.connectedPeers().isEmpty());
            assertTrue(carol.connectedPeers().isEmpty());
            assertThrows(IOException.class, () -> alice.broadcastMessage(first.id(), "é".repeat(32_769)));
            assertThrows(IOException.class, () -> alice.broadcastGlobalMessage("é".repeat(32_769)));
        }
    }

    @Test
    void communityFileUploadsOnceAndCanBeDownloadedAfterSenderLeaves() throws Exception {
        try (Fixture f = new Fixture(root)) {
            Events a = new Events(), b = new Events(), c = new Events(), outside = new Events();
            ClientSession alice = f.login("alice", a), bob = f.login("bob", b), carol = f.login("admin1", c);
            f.login("user01", outside);
            var group = alice.communities().create("Shared files");
            bob.communities().join(group.id()); carol.communities().join(group.id());
            Path source = Files.writeString(root.resolve("ghi chú.txt"), "Community file via server");
            alice.broadcastFile(group, source.toFile());
            FileOffer upload = a.offer();
            assertFalse(upload.incoming()); assertEquals(group.id(), upload.communityId());
            assertEquals(ClientEvents.FileState.COMPLETED, a.result(upload.id()).state());
            FileOffer forBob = b.offer(), forCarol = c.offer();
            assertEquals(forBob.id(), forCarol.id());
            assertEquals(group.id(), forBob.communityId());
            assertTrue(a.offers.isEmpty(), "One outgoing card per community file");
            assertTrue(outside.offers.isEmpty());
            alice.close();
            bob.accept(forBob.number()); carol.accept(forCarol.id());
            Result savedBob = b.result(forBob.id()), savedCarol = c.result(forCarol.id());
            assertEquals(ClientEvents.FileState.COMPLETED, savedBob.state());
            assertEquals(ClientEvents.FileState.COMPLETED, savedCarol.state());
            assertEquals(-1, Files.mismatch(source, Path.of(savedBob.detail())));
            assertEquals(-1, Files.mismatch(source, Path.of(savedCarol.detail())));
            assertTrue(bob.connectedPeers().isEmpty()); assertTrue(carol.connectedPeers().isEmpty());
            try (var stored = Files.list(root.resolve("storage"))) { assertEquals(1, stored.count()); }
        }
    }

    @Test
    void downloadsAreIndependentAndCancelingOneKeepsTheSharedFileAvailable() throws Exception {
        try (Fixture f = new Fixture(root)) {
            Events a = new Events(), b = new Events(), c = new Events();
            ClientSession alice = f.login("alice", a), bob = f.login("bob", b), carol = f.login("admin1", c);
            var group = alice.communities().create("Downloads");
            bob.communities().join(group.id()); carol.communities().join(group.id());
            Path source = root.resolve("large.bin");
            try (var file = new java.io.RandomAccessFile(source.toFile(), "rw")) { file.setLength(8 * 1024 * 1024); }
            alice.broadcastFile(group, source.toFile());
            assertEquals(ClientEvents.FileState.COMPLETED, a.result(a.offer().id()).state());
            FileOffer forBob = b.offer(), forCarol = c.offer();
            b.cancelOnProgress.add(forBob.id());
            bob.accept(forBob.id()); carol.accept(forCarol.id());
            assertEquals(ClientEvents.FileState.CANCELED, b.result(forBob.id()).state());
            Result saved = c.result(forCarol.id());
            assertEquals(ClientEvents.FileState.COMPLETED, saved.state());
            assertEquals(-1, Files.mismatch(source, Path.of(saved.detail())));
            try (var files = Files.list(root.resolve("bob"))) { assertEquals(0, files.count()); }
            alice.broadcastMessage(group.id(), "after cancel");
            assertEquals(group.id() + ":alice:after cancel", b.group.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void serverChecksMembershipAgainBeforeDownloadingAndAllowsRejectingAnOffer() throws Exception {
        try (Fixture f = new Fixture(root)) {
            Events a = new Events(), b = new Events(), c = new Events();
            ClientSession alice = f.login("alice", a), bob = f.login("bob", b), carol = f.login("admin1", c);
            var group = alice.communities().create("Permission");
            bob.communities().join(group.id()); carol.communities().join(group.id());
            Path empty = Files.write(root.resolve("empty.bin"), new byte[0]);
            alice.broadcastFile(group, empty.toFile());
            assertEquals(ClientEvents.FileState.COMPLETED, a.result(a.offer().id()).state());
            FileOffer forBob = b.offer(), forCarol = c.offer();
            bob.communities().leave(group.id());
            bob.accept(forBob.id()); carol.reject(forCarol.id());
            assertEquals(ClientEvents.FileState.FAILED, b.result(forBob.id()).state());
            assertEquals(ClientEvents.FileState.REJECTED, c.result(forCarol.id()).state());
            assertFalse(Files.exists(root.resolve("bob/empty.bin")));
            bob.broadcastFile(group, empty.toFile());
            assertEquals(ClientEvents.FileState.FAILED, b.result(b.offer().id()).state());
        }
    }

    @Test
    void privateChatAndFilesRemainPeerToPeerIncludingCancellation() throws Exception {
        try (Fixture f = new Fixture(root)) {
            Events a = new Events(), b = new Events();
            ClientSession alice = f.login("alice", a), bob = f.login("bob", b);
            alice.ensurePeer("bob");
            alice.disconnectServer(); bob.disconnectServer();
            await(() -> !alice.isServerConnected() && !bob.isServerConnected());
            assertTrue(alice.sendMessage("private"));
            assertEquals("alice:private", b.direct.poll(5, TimeUnit.SECONDS));
            Path source = Files.writeString(root.resolve("private.txt"), "still P2P");
            alice.sendFile("bob", source.toFile());
            FileOffer offer = b.offer(); assertNull(offer.communityId());
            bob.accept(offer.id());
            Result saved = b.result(offer.id());
            assertEquals(ClientEvents.FileState.COMPLETED, saved.state());
            assertEquals(-1, Files.mismatch(source, Path.of(saved.detail())));
            assertEquals(ClientEvents.FileState.COMPLETED, a.result(offer.id()).state());
            Path large = root.resolve("cancel.bin");
            try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) { file.setLength(8 * 1024 * 1024); }
            alice.sendFile("bob", large.toFile());
            FileOffer canceled = b.offer(); b.cancelOnProgress.add(canceled.id());
            bob.accept(canceled.id());
            assertEquals(ClientEvents.FileState.CANCELED, b.result(canceled.id()).state());
            assertEquals(ClientEvents.FileState.CANCELED, a.result(canceled.id()).state());
        }
    }

    @Test
    void duplicateCommunityNamesReportAnErrorAndAllowRetryWithoutChangingSelection() throws Exception {
        try (Fixture f = new Fixture(root)) {
            ClientSession alice = f.login("alice", new Events()), bob = f.login("bob", new Events());
            var existing = alice.communities().create("Study Group");
            IOException sameOwner = assertThrows(IOException.class,
                    () -> alice.communities().create(" study group "));
            assertTrue(sameOwner.getMessage().contains("Please enter a different name"));
            assertEquals(existing, alice.communities().selected());
            IOException otherOwner = assertThrows(IOException.class,
                    () -> bob.communities().create("STUDY GROUP"));
            assertTrue(otherOwner.getMessage().contains("Please enter a different name"));
            assertNull(bob.communities().selected());
            assertEquals(1, bob.communities().list().size());
            var retry = bob.communities().create("Another Group");
            assertNotEquals(existing.id(), retry.id());
            assertEquals(retry, bob.communities().selected());
            assertEquals(2, alice.communities().list().size());
            assertTrue(bob.isServerConnected());
        }
    }

    private record Result(ClientEvents.FileState state, String detail) {}

    private static class Events implements ClientEvents {
        final BlockingQueue<String> group = new LinkedBlockingQueue<>(), direct = new LinkedBlockingQueue<>(),
                global = new LinkedBlockingQueue<>();
        final BlockingQueue<FileOffer> offers = new LinkedBlockingQueue<>();
        final Map<String, Result> results = new ConcurrentHashMap<>();
        final Set<String> cancelOnProgress = ConcurrentHashMap.newKeySet();
        ClientSession session;
        public void onBroadcast(String id, String sender, String content) { group.add(id + ":" + sender + ":" + content); }
        public void onMessage(String sender, String content) { direct.add(sender + ":" + content); }
        public void onGlobalBroadcast(String sender, String content) { global.add(sender + ":" + content); }
        public void onFileOffer(FileOffer offer) { offers.add(offer); }
        public void onFileProgress(String id, long transferred, long total, double speed, long seconds) {
            if (transferred > 0 && cancelOnProgress.remove(id)) {
                try { session.cancelFile(id); } catch (IOException e) { throw new AssertionError(e); }
            }
        }
        public void onFileState(String id, ClientEvents.FileState state, String detail) {
            if (state != ClientEvents.FileState.TRANSFERRING && state != ClientEvents.FileState.CANCELING) {
                results.put(id, new Result(state, detail));
            }
        }
        FileOffer offer() throws InterruptedException {
            FileOffer offer = offers.poll(5, TimeUnit.SECONDS);
            assertNotNull(offer, "Missing file offer"); return offer;
        }
        Result result(String id) throws Exception {
            await(() -> results.containsKey(id)); return results.get(id);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Timed out");
    }

    private static class Fixture implements AutoCloseable {
        final Path root;
        final ServerKeyPinning pinning;
        final Server server;
        final Thread thread;
        final List<ClientSession> clients = new ArrayList<>();
        Fixture(Path root) throws Exception {
            this.root = root;
            Path identity = root.resolve("identity");
            ServerIdentity.generate().save(identity);
            pinning = new ServerKeyPinning(identity.resolve("server-public.key"));
            server = new Server(identity, root.resolve("storage"), 0);
            thread = Thread.startVirtualThread(() -> server.start(0));
            await(() -> server.port() > 0);
        }
        ClientSession login(String name, Events events) throws IOException {
            String password = switch (name) {
                case "alice", "bob" -> name + "123";
                case "admin1" -> "password1";
                default -> "demo123";
            };
            var session = new Client(pinning, root.resolve(name).toFile())
                    .login("127.0.0.1", server.port(), name, password, events);
            clients.add(session); events.session = session; session.start(); return session;
        }
        public void close() throws Exception {
            clients.forEach(ClientSession::close); server.close(); thread.join(Duration.ofSeconds(3));
        }
    }
}
