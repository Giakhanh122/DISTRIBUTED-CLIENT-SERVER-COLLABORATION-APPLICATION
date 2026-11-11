import client.Client;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import protocols.OpCode;
import protocols.Packet;
import protocols.Json;
import com.google.gson.JsonObject;
import protocols.PacketCodec;
import protocols.PacketValidator;
import server.auth.AuthService;
import server.connection.ClientHandler;
import server.session.ChatSession;
import server.session.ChatSessionRegistry;
import server.session.UserSession;
import server.session.UserSessionRegistry;

public class SessionLifecycleTest {
    private static final ByteArrayOutputStream transcript = new ByteArrayOutputStream();

    public static void main(String[] args) throws Exception {
        PrintStream console = System.out;
        System.setOut(new PrintStream(new OutputStream() {
            @Override public void write(int value) {
                console.write(value);
                transcript.write(value);
            }
            @Override public void write(byte[] bytes, int offset, int length) {
                console.write(bytes, offset, length);
                transcript.write(bytes, offset, length);
            }
        }, true, StandardCharsets.UTF_8));
        registryLifecycle();
        concurrentReports();
        realClients();
        multiPeerLogout();
        System.out.println("PASS: all session tests");
    }

    private static void registryLifecycle() {
        UserSession alice = new UserSession("alice", "127.0.0.1", 10001, null);
        UserSession bob = new UserSession("bob", "127.0.0.1", 10002, null);
        UserSession admin = new UserSession("admin1", "127.0.0.1", 10003, null);
        UserSessionRegistry users = new UserSessionRegistry();
        check(users.register(alice) && users.register(bob), "register online users");
        check(!users.register(new UserSession("alice", "127.0.0.1", 20001, null)), "reject duplicate login");
        ChatSessionRegistry chats = new ChatSessionRegistry();
        UUID first = UUID.randomUUID();
        check(chats.connected(first, alice, bob), "first report accepted");
        ChatSession initial = chats.find(first).orElseThrow();
        check(initial.state() == ChatSession.State.CONNECTED, "one post-handshake report records connection");
        check(chats.connected(first, bob, alice), "second report accepted");
        check(chats.listSessions().size() == 1, "two reports create one session");
        check(!chats.connected(first, admin, alice), "third user cannot take session ID");
        check(!chats.disconnected(first, admin, "alice"), "third user cannot close session");
        check(!chats.connected(first, alice, admin), "participant pair cannot change");
        check(!chats.connected(UUID.randomUUID(), alice, alice), "self sessions rejected");
        check(chats.connected(first, alice, bob)
            && chats.find(first).orElseThrow().state() == ChatSession.State.CONNECTED,
            "duplicate reports preserve active connection");
        check(chats.disconnected(first, alice, "bob"), "participant can close session");
        check(initial.state() == ChatSession.State.CONNECTED, "snapshots are immutable");
        chats.connected(first, bob, alice);
        check(chats.find(first).orElseThrow().state() == ChatSession.State.DISCONNECTED,
            "late report cannot revive closed connection");
        chats.disconnected(first, bob, "alice");
        check(chats.find(first).orElseThrow().state() == ChatSession.State.DISCONNECTED,
            "repeated close preserves disconnected state");

        UUID replacement = UUID.randomUUID();
        chats.connected(replacement, alice, bob);
        chats.connected(replacement, bob, alice);
        chats.disconnected(first, alice, "bob");
        check(chats.find(replacement).orElseThrow().state() == ChatSession.State.CONNECTED,
            "old close cannot affect replacement connection");
        users.remove(alice);
        chats.userDisconnected(alice);
        check(chats.find(replacement).orElseThrow().state() == ChatSession.State.UNKNOWN,
            "management disconnect does not prove P2P disconnect");
        UserSession newAlice = new UserSession("alice", "127.0.0.1", 10001, null);
        users.register(newAlice);
        check(!chats.connected(replacement, newAlice, bob), "new login cannot reuse old session ID");
        UUID newLoginChat = UUID.randomUUID();
        chats.connected(newLoginChat, newAlice, bob);
        chats.connected(newLoginChat, bob, newAlice);
        chats.userDisconnected(alice);
        check(chats.find(newLoginChat).orElseThrow().state() == ChatSession.State.CONNECTED,
            "old login cleanup does not affect new login");
        check(chats.disconnected(replacement, bob, "alice"), "remaining user can close unknown session");
        System.out.println("PASS: separate registries, ownership and session lifecycle");
    }

    private static void concurrentReports() throws Exception {
        ChatSessionRegistry chats = new ChatSessionRegistry();
        UserSession alice = new UserSession("alice", "127.0.0.1", 10001, null);
        UserSession bob = new UserSession("bob", "127.0.0.1", 10002, null);
        UUID id = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> reporters = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean fromAlice = i % 2 == 0;
            reporters.add(Thread.startVirtualThread(() -> {
                try {
                    start.await();
                    chats.connected(id, fromAlice ? alice : bob, fromAlice ? bob : alice);
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }));
        }
        start.countDown();
        for (Thread thread : reporters) thread.join();
        check(chats.listSessions().size() == 1
            && chats.find(id).orElseThrow().state() == ChatSession.State.CONNECTED,
            "simultaneous reports merge atomically");
        System.out.println("PASS: simultaneous session reports");
    }

    private static void realClients() throws Exception {
        try (TestServer server = new TestServer();
             ConsoleClient alice = new ConsoleClient(server.port(), "alice", "alice123");
             ConsoleClient bob = new ConsoleClient(server.port(), "bob", "bob123");
             ManagementClient admin = new ManagementClient(server.port())) {
            await(() -> server.users.listOnlineUsers().size() == 3, "three users logged in");
            connectPeer(alice, "bob");
            await(() -> server.chats.listSessions().stream()
                .anyMatch(chat -> chat.state() == ChatSession.State.CONNECTED), "P2P reports registered");
            check(server.chats.listSessions().size() == 1, "one registry entry for two clients");
            UUID first = server.chats.listSessions().getFirst().sessionId();
            var listing = Json.array(admin.request(OpCode.CHAT_SESSION_LIST_REQUEST, "",
                OpCode.CHAT_SESSION_LIST_RESPONSE).payload(), "sessions");
            check(listing.size() == 1 && Json.string(listing.get(0).getAsJsonObject(), "sessionId").equals(first.toString())
                && Json.string(listing.get(0).getAsJsonObject(), "state").equals("CONNECTED"), "server returns JSON session metadata");
            check(admin.report(first + "\nalice\nDISCONNECTED").equals("INVALID_SESSION"),
                "wire report cannot close another pair's session");
            check(admin.report(first + "\nalice\nCONNECTED").equals("INVALID_SESSION"),
                "wire report cannot hijack another pair's session");
            check(admin.report("bad-id\nalice\nCONNECTED").equals("INVALID_SESSION_REPORT"),
                "malformed ID rejected without dropping login");
            check(admin.report(UUID.randomUUID() + "\nnobody\nCONNECTED").equals("USER_OFFLINE"),
                "offline peer rejected");
            check(Json.array(admin.request(OpCode.USER_LIST_REQUEST, "", OpCode.USER_LIST_RESPONSE).payload(), "users").contains(new com.google.gson.JsonPrimitive("alice")),
                "existing user list still works");

            alice.command("hello-from-alice");
            bob.command("hello-from-bob");
            await(() -> output().contains("alice: hello-from-alice")
                && output().contains("bob: hello-from-bob"), "bidirectional direct chat");
            alice.command("/chats");
            await(() -> output().contains("id=" + first), "client /chats displays metadata");

            byte[] data = new byte[131_081];
            new Random(7).nextBytes(data);
            Path upload = Path.of("session-upload.bin").toAbsolutePath();
            Files.write(upload, data);
            alice.command("/sendfile bob " + upload);
            await(() -> output().contains("[FILE OFFER #1]"), "P2P file offer received");
            bob.command("/accept 1");
            await(() -> output().contains("[P2P SENT] session-upload.bin completed!"), "P2P file acknowledged");
            check(Arrays.equals(data, Files.readAllBytes(Path.of("downloads/bob/session-upload.bin"))),
                "binary file remains intact after handshake change");

            // Simulate the close report before requesting a replacement connection;
            // the server rejects lookup while metadata still says CONNECTED.
            check(server.chats.disconnected(first, server.users.find("alice").orElseThrow(), "bob"),
                "close first session before replacement lookup");
            connectPeer(alice, "bob");
            await(() -> server.chats.listSessions().size() == 2
                && server.chats.find(first).orElseThrow().state() == ChatSession.State.DISCONNECTED
                && server.chats.listSessions().stream()
                    .filter(chat -> chat.state() == ChatSession.State.CONNECTED).count() == 1,
                "replacement connection has separate history");
            UUID second = server.chats.listSessions().stream()
                .filter(chat -> chat.state() == ChatSession.State.CONNECTED).findFirst().orElseThrow().sessionId();
            bob.close();
            await(() -> server.chats.find(second).orElseThrow().state() == ChatSession.State.DISCONNECTED,
                "logout closes P2P metadata before removing user");
            await(() -> server.users.find("bob").isEmpty(), "logout removes online user");
            System.out.println("PASS: real clients, /chats, validation, chat, file and logout");
        }

        try (TestServer server = new TestServer();
             ConsoleClient alice = new ConsoleClient(server.port(), "alice", "alice123");
             ConsoleClient bob = new ConsoleClient(server.port(), "bob", "bob123")) {
            await(() -> server.users.listOnlineUsers().size() == 2, "users logged in before server loss");
            connectPeer(alice, "bob");
            await(() -> server.chats.listSessions().stream()
                .anyMatch(chat -> chat.state() == ChatSession.State.CONNECTED), "P2P active before server loss");
            UUID id = server.chats.listSessions().getFirst().sessionId();
            server.dropConnections();
            await(() -> server.users.listOnlineUsers().isEmpty()
                && server.chats.find(id).orElseThrow().state() == ChatSession.State.UNKNOWN,
                "server loss marks metadata unknown");
            alice.command("after-server-loss-alice");
            bob.command("after-server-loss-bob");
            await(() -> output().contains("alice: after-server-loss-alice")
                && output().contains("bob: after-server-loss-bob"), "P2P survives management socket loss");
            System.out.println("PASS: management disconnect preserves direct P2P chat");
        }
    }

    private static String output() {
        return transcript.toString(StandardCharsets.UTF_8);
    }

    private static void connectPeer(ConsoleClient client, String peer) throws Exception {
        int offset = output().length();
        client.command("/lookup " + peer);
        // One report now marks CONNECTED; wait for both consoles before sending test commands.
        await(() -> {
            String recent = output().substring(offset);
            return recent.contains("Now chatting with " + peer)
                && recent.contains("Peer handshake successful with " + client.username);
        }, "both clients finished the P2P handshake");
    }

    private static void multiPeerLogout() throws Exception {
        try (TestServer server = new TestServer();
             ConsoleClient alice = new ConsoleClient(server.port(), "alice", "alice123");
             ConsoleClient bob = new ConsoleClient(server.port(), "bob", "bob123");
             ConsoleClient admin = new ConsoleClient(server.port(), "admin1", "password1")) {
            await(() -> server.users.listOnlineUsers().size() == 3, "three P2P clients logged in");
            connectPeer(alice, "bob");
            await(() -> server.chats.listSessions().stream()
                .filter(chat -> chat.state() == ChatSession.State.CONNECTED).count() == 1,
                "first direct connection established");
            connectPeer(alice, "admin1");
            await(() -> server.chats.listSessions().stream()
                .filter(chat -> chat.state() == ChatSession.State.CONNECTED).count() == 2,
                "second direct connection established");
            alice.close();
            await(() -> server.users.find("alice").isEmpty()
                && server.chats.listSessions().stream()
                    .allMatch(chat -> chat.state() == ChatSession.State.DISCONNECTED),
                "logout reports every closed connection before removing user");
            check(server.users.find("bob").isPresent() && server.users.find("admin1").isPresent(),
                "other online sessions remain registered");
            System.out.println("PASS: logout reports multiple P2P sessions before server disconnect");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out: " + message);
            Thread.sleep(10);
        }
    }

    private static class TestServer implements AutoCloseable {
        final UserSessionRegistry users = new UserSessionRegistry();
        final ChatSessionRegistry chats = new ChatSessionRegistry();
        final ServerSocket listener = new ServerSocket(0);
        final List<Socket> sockets = new CopyOnWriteArrayList<>();
        final List<Thread> handlers = new CopyOnWriteArrayList<>();
        final Thread acceptor;

        TestServer() throws IOException {
            acceptor = Thread.startVirtualThread(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket socket = listener.accept();
                        sockets.add(socket);
                        handlers.add(Thread.startVirtualThread(new ClientHandler(socket, users, chats, new AuthService())));
                    } catch (IOException e) {
                        if (!listener.isClosed()) throw new AssertionError(e);
                    }
                }
            });
        }

        int port() { return listener.getLocalPort(); }

        void dropConnections() throws IOException {
            for (Socket socket : sockets) socket.close();
        }

        @Override public void close() throws Exception {
            listener.close();
            acceptor.join(1000);
            dropConnections();
            for (Thread thread : handlers) thread.join(1000);
        }
    }

    private static class ConsoleClient implements AutoCloseable {
        final PipedWriter console = new PipedWriter();
        final Thread thread;
        final String username;

        ConsoleClient(int port, String username, String password) throws IOException {
            this.username = username;
            PipedReader input = new PipedReader(console);
            thread = Thread.startVirtualThread(() -> {
                try (Scanner scanner = new Scanner(input)) {
                    new Client().connectToServer("127.0.0.1", port, scanner);
                }
            });
            command(username);
            command(password);
        }

        void command(String command) throws IOException {
            console.write(command + "\n");
            console.flush();
        }

        @Override public void close() throws Exception {
            if (thread.isAlive()) {
                command("/logout");
                thread.join(3000);
                check(!thread.isAlive(), "client exits after logout");
            }
            console.close();
        }
    }

    private static class ManagementClient implements AutoCloseable {
        final Socket socket;
        int requestId;

        ManagementClient(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(3000);
            request(OpCode.HELLO, "", OpCode.HELLO_ACK);
            check(request(OpCode.AUTH_REQUEST, "admin1\npassword1\n12345", OpCode.AUTH_RESPONSE).status().equals("OK"),
                "admin login");
        }

        Packet request(OpCode op, String payload, OpCode expected) throws IOException {
            int id = ++requestId;
            JsonObject fields = new JsonObject();
            if (op == OpCode.AUTH_REQUEST) {
                String[] parts = payload.split("\n", -1);
                fields = Json.object("username", parts[0], "password", parts[1], "peerPort", Integer.parseInt(parts[2]));
            } else if (op == OpCode.CHAT_SESSION_REPORT) {
                String[] parts = payload.split("\n", -1);
                fields = Json.object("sessionId", parts[0], "peerUsername", parts[1], "state", parts[2]);
            }
            PacketCodec.write(socket.getOutputStream(), Packet.json(op, id, fields));
            return PacketValidator.validateResponse(PacketCodec.read(socket.getInputStream()), expected, id);
        }

        String report(String payload) throws IOException {
            return request(OpCode.CHAT_SESSION_REPORT, payload, OpCode.CHAT_SESSION_REPORT_RESPONSE).status();
        }

        @Override public void close() throws IOException { socket.close(); }
    }
}
