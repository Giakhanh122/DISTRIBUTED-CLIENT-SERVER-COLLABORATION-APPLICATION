package client;

import static org.junit.jupiter.api.Assertions.*;

import client.community.CommunityClient;
import client.security.ServerKeyPinning;
import client.transfer.FileOffer;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import server.Server;
import server.security.ServerIdentity;

@Timeout(90)
class AssignmentIntegrationTest {
    @TempDir Path temp;

    @Test
    void twentyAuthenticatedClientsSurviveMalformedConnectionsAndIndividualDisconnects() throws Exception {
        try (Fixture f = new Fixture(temp); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<ClientSession>> logins = new ArrayList<>();
            for (String name : users()) logins.add(() -> f.login(name, new Events()));
            List<ClientSession> sessions = new ArrayList<>();
            for (Future<ClientSession> result : pool.invokeAll(logins)) sessions.add(result.get());
            await(() -> {
                try { return sessions.getFirst().users().size() == 20; }
                catch (IOException e) { return false; }
            });
            try (Socket invalid = new Socket("127.0.0.1", f.server.port())) {
                new DataOutputStream(invalid.getOutputStream()).writeInt(-1);
            }
            assertThrows(IOException.class, () -> f.client("intruder").login("127.0.0.1", f.server.port(),
                    "alice", "incorrect", ClientEvents.NONE));
            sessions.getLast().disconnectServer();
            await(() -> sessions.getLast().connectionState() == ConnectionState.SERVER_OFFLINE);
            await(() -> {
                try { return sessions.getFirst().users().size() == 19; }
                catch (IOException e) { return false; }
            });
            assertEquals(ConnectionState.ONLINE, sessions.getFirst().connectionState());
        }
    }

    @Test
    void concurrentP2pFilesIncluding200MiBEmptyAndUnicodePreserveContents() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            Events a = new Events(), b = new Events();
            ClientSession alice = f.login("alice", a), bob = f.login("bob", b);
            alice.ensurePeer("bob");
            List<Path> sources = List.of(large("large-200MiB.bin", 200),
                    Files.writeString(temp.resolve("ghi chú.txt"), "Tiếng Việt: truyền file an toàn"),
                    Files.write(temp.resolve("empty.bin"), new byte[0]));
            for (Path source : sources) alice.sendFile("bob", source.toFile());
            List<FileOffer> offers = new ArrayList<>();
            for (int i = 0; i < sources.size(); i++) {
                FileOffer offer = b.offer();
                offers.add(offer);
            }
            offers.sort(java.util.Comparator.comparingLong((FileOffer offer) -> offer.meta().fileSize()).reversed());
            for (FileOffer offer : offers) bob.accept(offer.id());
            for (FileOffer offer : offers) {
                Result received = b.result(offer.id());
                assertEquals(ClientEvents.FileState.COMPLETED, received.state());
                Path source = sources.stream().filter(p -> p.getFileName().toString().equals(offer.meta().fileName()))
                        .findFirst().orElseThrow();
                assertEquals(-1, Files.mismatch(source, Path.of(received.detail())));
                assertEquals(ClientEvents.FileState.COMPLETED, a.result(offer.id()).state());
                assertTrue(b.progress.containsKey(offer.id()));
            }
            assertTrue(b.maxActive.get() >= 2, "Transfers were serialized instead of overlapping");
            assertNoPartialFiles(f.downloads("bob"));
        }
    }

    @Test
    void cancelP2pCleansPartialFileAndLeavesOtherTransfersAndChatUsable() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            Events a = new Events(), b = new Events();
            ClientSession alice = f.login("alice", a), bob = f.login("bob", b);
            alice.ensurePeer("bob");
            Files.createDirectories(f.downloads("bob"));
            Path existing = Files.writeString(f.downloads("bob").resolve("cancel.bin"), "keep existing file");
            alice.sendFile("bob", large("cancel.bin", 32).toFile());
            FileOffer canceled = b.offer();
            b.cancelOnProgress.add(canceled.id());
            bob.accept(canceled.id());
            assertEquals(ClientEvents.FileState.CANCELED, b.result(canceled.id()).state());
            assertEquals("keep existing file", Files.readString(existing));
            assertNoPartialFiles(f.downloads("bob"));
            try (var files = Files.list(f.downloads("bob"))) { assertEquals(1, files.count()); }
            Path next = Files.writeString(temp.resolve("next.txt"), "after cancellation");
            alice.sendFile("bob", next.toFile());
            FileOffer nextOffer = b.offer();
            bob.accept(nextOffer.id());
            assertEquals(ClientEvents.FileState.COMPLETED, b.result(nextOffer.id()).state());
            alice.sendMessage("bob", "still connected");
            assertEquals("still connected", b.messages.poll(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void communityUploadDownloadAndCancellationUseIndependentDataConnections() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            Events a = new Events(), b = new Events();
            ClientSession alice = f.login("alice", a), bob = f.login("bob", b);
            CommunityClient.Selection group = alice.communities().create("File validation");
            bob.communities().join(group.id());
            Path source = large("community-200MiB.bin", 200);
            alice.broadcastFile(group, source.toFile());
            FileOffer uploaded = a.offer();
            assertEquals(ClientEvents.FileState.COMPLETED, a.result(uploaded.id()).state());
            FileOffer download = b.offer();
            bob.accept(download.id());
            Result saved = b.result(download.id());
            assertEquals(ClientEvents.FileState.COMPLETED, saved.state());
            assertEquals(-1, Files.mismatch(source, Path.of(saved.detail())));
            assertTrue(a.progress.containsKey(uploaded.id()));
            assertTrue(b.progress.containsKey(download.id()));
            alice.broadcastFile(group, large("community-cancel.bin", 32).toFile());
            FileOffer secondUpload = a.offer();
            assertEquals(ClientEvents.FileState.COMPLETED, a.result(secondUpload.id()).state());
            FileOffer cancel = b.offer();
            b.cancelOnProgress.add(cancel.id());
            bob.accept(cancel.id());
            assertEquals(ClientEvents.FileState.CANCELED, b.result(cancel.id()).state());
            assertFalse(Files.exists(f.downloads("bob").resolve("community-cancel.bin")));
            assertNoPartialFiles(f.downloads("bob"));
            assertEquals(2, alice.users().size());
        }
    }

    @Test
    void canceledCommunityUploadLeavesNoServerTemporaryOrCommittedFile() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            Events events = new Events();
            ClientSession alice = f.login("alice", events);
            var group = alice.communities().create("Canceled upload");
            events.cancelOutgoingFiles = true;
            alice.broadcastFile(group, large("cancel-upload.bin", 32).toFile());
            FileOffer offer = events.offer();
            assertEquals(ClientEvents.FileState.CANCELED, events.result(offer.id()).state());
            await(() -> {
                try (var files = Files.list(temp.resolve("storage"))) { return files.findAny().isEmpty(); }
                catch (IOException e) { return false; }
            });
            assertTrue(alice.isServerConnected());
        }
    }

    @Test
    void untrustedServerKeyIsRejectedBeforeLogin() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            Path wrongIdentity = temp.resolve("untrusted-identity");
            ServerIdentity.generate().save(wrongIdentity);
            Client wrongTrust = new Client(new ServerKeyPinning(wrongIdentity.resolve("server-public.key")),
                    temp.resolve("untrusted-downloads").toFile());
            IOException error = assertThrows(IOException.class, () -> wrongTrust.login("127.0.0.1", f.server.port(),
                    "alice", "alice123", ClientEvents.NONE));
            assertTrue(error.getMessage().contains("signature verification"));
        }
    }

    @Test
    void serverRestartAndFailedReconnectPreserveExistingPeerConnection() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            Events a = new Events(), b = new Events();
            ClientSession alice = f.login("alice", a), bob = f.login("bob", b);
            alice.ensurePeer("bob");
            await(() -> bob.isPeerConnected("alice"));
            int peerPort = alice.peerPort();
            f.restart();
            await(() -> alice.connectionState() == ConnectionState.SERVER_OFFLINE);
            alice.sendMessage("bob", "without server");
            assertEquals("without server", b.messages.poll(3, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> alice.reconnect("wrong-password"));
            assertEquals(ConnectionState.SERVER_OFFLINE, alice.connectionState());
            alice.reconnect("alice123");
            bob.reconnect("bob123");
            assertEquals(peerPort, alice.peerPort());
            assertEquals(2, alice.users().size());
            assertTrue(alice.isPeerConnected("bob"));
        }
    }

    private Path large(String name, int mib) throws IOException {
        Path path = temp.resolve(name);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(mib * 1024L * 1024);
            file.seek(0); file.writeUTF("binary fixture " + name);
            file.seek(file.length() - 1); file.write(127);
        }
        return path;
    }

    private static List<String> users() {
        List<String> names = new ArrayList<>(List.of("alice", "bob", "admin1"));
        for (int i = 1; i <= 17; i++) names.add(String.format(Locale.ROOT, "user%02d", i));
        return names;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition did not become true");
    }

    private static void assertNoPartialFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".transfer-")));
        }
    }

    private record Result(ClientEvents.FileState state, String detail) {}
    private static final class Events implements ClientEvents {
        final BlockingQueue<FileOffer> offers = new LinkedBlockingQueue<>();
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final Map<String, CompletableFuture<Result>> results = new ConcurrentHashMap<>();
        final Map<String, Long> progress = new ConcurrentHashMap<>();
        final Set<String> cancelOnProgress = ConcurrentHashMap.newKeySet();
        final Set<String> activeFiles = ConcurrentHashMap.newKeySet();
        final AtomicInteger maxActive = new AtomicInteger();
        volatile ClientSession session;
        volatile boolean cancelOutgoingFiles;
        public void onFileOffer(FileOffer offer) {
            if (!offer.incoming() && cancelOutgoingFiles) cancelOnProgress.add(offer.id());
            offers.add(offer);
        }
        public void onMessage(String peer, String content) { messages.add(content); }
        public void onFileState(String id, FileState state, String detail) {
            if (state == FileState.TRANSFERRING) {
                activeFiles.add(id);
                maxActive.accumulateAndGet(activeFiles.size(), Math::max);
            } else if (state != FileState.CANCELING) activeFiles.remove(id);
            if (state != FileState.TRANSFERRING && state != FileState.CANCELING)
                results.computeIfAbsent(id, key -> new CompletableFuture<>()).complete(new Result(state, detail));
        }
        public void onFileProgress(String id, long transferred, long total, double speed, long remaining) {
            progress.put(id, transferred);
            if (transferred > 0 && cancelOnProgress.remove(id)) {
                try { session.cancelFile(id); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            }
        }
        FileOffer offer() throws InterruptedException {
            FileOffer result = offers.poll(30, TimeUnit.SECONDS);
            assertNotNull(result, "No file offer arrived");
            return result;
        }
        Result result(String id) throws Exception {
            return results.computeIfAbsent(id, key -> new CompletableFuture<>()).get(40, TimeUnit.SECONDS);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final ServerKeyPinning pinning;
        final List<ClientSession> clients = new CopyOnWriteArrayList<>();
        Server server;
        Thread thread;
        Fixture(Path root) throws Exception {
            this.root = root;
            Path identity = root.resolve("identity");
            ServerIdentity.generate().save(identity);
            pinning = new ServerKeyPinning(identity.resolve("server-public.key"));
            start(0);
        }
        Client client(String name) { return new Client(pinning, downloads(name).toFile()); }
        Path downloads(String name) { return root.resolve("downloads").resolve(name); }
        ClientSession login(String name, Events events) throws IOException {
            String password = switch (name) {
                case "alice" -> "alice123";
                case "bob" -> "bob123";
                case "admin1" -> "password1";
                default -> "demo123";
            };
            ClientSession session = client(name).login("127.0.0.1", server.port(), name, password, events);
            clients.add(session);
            events.session = session;
            session.start();
            return session;
        }
        void start(int port) throws Exception {
            server = new Server(root.resolve("identity"), root.resolve("storage"), 0);
            thread = Thread.startVirtualThread(() -> server.start(port));
            await(() -> server.port() > 0);
        }
        void restart() throws Exception {
            int port = server.port();
            server.close(); thread.join(Duration.ofSeconds(3));
            start(port);
        }
        public void close() throws Exception {
            clients.forEach(ClientSession::close);
            server.close(); thread.join(Duration.ofSeconds(3));
        }
    }
}
