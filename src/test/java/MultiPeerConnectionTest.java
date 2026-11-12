import client.Client;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import server.auth.AuthService;
import server.connection.ClientHandler;
import server.session.ChatSession;
import server.session.ChatSessionRegistry;
import server.session.UserSessionRegistry;

public class MultiPeerConnectionTest {

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

        testAllTwelveRequirements();
        System.out.println("PASS: ALL 12 MULTI-PEER REQUIREMENTS SUCCESSFULLY TESTED AND VERIFIED!");
    }

    private static void testAllTwelveRequirements() throws Exception {
        try (TestServer server = new TestServer();
             TestClient admin1 = new TestClient(server.port(), "admin1", "password1");
             TestClient alice = new TestClient(server.port(), "alice", "alice123");
             TestClient bob = new TestClient(server.port(), "bob", "bob123")) {

            await(() -> server.users.listOnlineUsers().size() == 3, "All 3 users logged in to server");

            // 1. Connect to Alice
            System.out.println("\n--- [Step 1: Connect to Alice] ---");
            int offset1 = admin1.getOutput().length();
            admin1.command("/lookup alice");
            await(() -> admin1.getOutput().substring(offset1).contains("Now chatting with alice")
                     && alice.getOutput().contains("Peer handshake successful with admin1"),
                "admin1 connected to Alice");

            // 2. Connect to Bob without disconnecting Alice
            System.out.println("\n--- [Step 2: Connect to Bob without disconnecting Alice] ---");
            int offset2 = admin1.getOutput().length();
            admin1.command("/lookup bob");
            await(() -> admin1.getOutput().substring(offset2).contains("Now chatting with bob")
                     && bob.getOutput().contains("Peer handshake successful with admin1"),
                "admin1 connected to Bob");

            // 3. Verify both connections exist simultaneously
            System.out.println("\n--- [Step 3: Verify both connections exist simultaneously] ---");
            await(() -> admin1.getClient().getPeerRegistry().isConnected("alice")
                     && admin1.getClient().getPeerRegistry().isConnected("bob"),
                "admin1 maintains connections to both Alice and Bob simultaneously");
            check(admin1.getClient().getPeerRegistry().getConnectedPeers().size() == 2,
                "Registry has exactly 2 connected peers");

            // 11 (part 1). Verify /peers reflects correct connection state
            System.out.println("\n--- [Step 11 (part 1): Verify /peers] ---");
            int offsetPeers = admin1.getOutput().length();
            admin1.command("/peers");
            await(() -> {
                String out = admin1.getOutput().substring(offsetPeers);
                return out.contains("- alice (CONNECTED)") && out.contains("- bob (CONNECTED)");
            }, "/peers displays both Alice and Bob as CONNECTED");

            // 4. Switch active peer between Alice and Bob
            System.out.println("\n--- [Step 4: Switch active peer between Alice and Bob] ---");
            int offsetPeerSwitch = admin1.getOutput().length();
            admin1.command("/peer alice");
            await(() -> admin1.getOutput().substring(offsetPeerSwitch).contains("Active peer set to alice"),
                "Active peer set to alice");
            check("alice".equals(admin1.getClient().getPeerRegistry().getActivePeer()),
                "admin1 active peer is alice");

            int offsetPeerQuery = admin1.getOutput().length();
            admin1.command("/peer");
            await(() -> admin1.getOutput().substring(offsetPeerQuery).contains("Active peer: alice"),
                "/peer displays active peer as alice");

            admin1.command("/peer bob");
            await(() -> "bob".equals(admin1.getClient().getPeerRegistry().getActivePeer()),
                "admin1 active peer switched to bob");

            // 5. Send a message to Alice explicitly
            System.out.println("\n--- [Step 5: Send a message to Alice explicitly] ---");
            admin1.command("/msg alice hello-explicit-alice");
            await(() -> alice.hasReceived("admin1: hello-explicit-alice"),
                "Alice received explicit message from admin1");
            check(!bob.hasReceived("hello-explicit-alice"),
                "Bob did NOT receive message intended for Alice");
            check("bob".equals(admin1.getClient().getPeerRegistry().getActivePeer()),
                "Active peer remains bob after explicit message to alice");

            // 6. Send a message to Bob explicitly
            System.out.println("\n--- [Step 6: Send a message to Bob explicitly] ---");
            admin1.command("/msg bob hello-explicit-bob");
            await(() -> bob.hasReceived("admin1: hello-explicit-bob"),
                "Bob received explicit message from admin1");
            check(!alice.hasReceived("hello-explicit-bob"),
                "Alice did NOT receive message intended for Bob");
            check("bob".equals(admin1.getClient().getPeerRegistry().getActivePeer()),
                "Active peer remains bob");

            // 7. Send a message using the active peer
            System.out.println("\n--- [Step 7: Send a message using the active peer] ---");
            admin1.command("/msg hello-default-to-bob");
            await(() -> bob.hasReceived("admin1: hello-default-to-bob"),
                "Bob (active peer) received message sent without username");
            check(!alice.hasReceived("hello-default-to-bob"),
                "Alice did NOT receive message sent to active peer Bob");

            // Also test bare text routed to active peer
            admin1.command("bare-text-to-bob");
            await(() -> bob.hasReceived("admin1: bare-text-to-bob"),
                "Bob received bare text routed to active peer");

            // 8. Send files to different peers
            System.out.println("\n--- [Step 8: Send files to different peers] ---");
            Path fileForAlice = Path.of("file-for-alice.txt").toAbsolutePath();
            Files.writeString(fileForAlice, "Hello Alice File Content 12345");
            Path fileForBob = Path.of("file-for-bob.txt").toAbsolutePath();
            Files.writeString(fileForBob, "Hello Bob File Content 67890");

            int aliceOfferOffset = alice.getOutput().length();
            admin1.command("/sendfile alice " + fileForAlice);
            await(() -> alice.getOutput().substring(aliceOfferOffset).contains("[FILE OFFER #1]"),
                "Alice received file offer from admin1");

            int bobOfferOffset = bob.getOutput().length();
            admin1.command("/sendfile bob " + fileForBob);
            await(() -> bob.getOutput().substring(bobOfferOffset).contains("[FILE OFFER #1]"),
                "Bob received file offer from admin1");

            // Alice accepts
            alice.command("/accept 1");
            await(() -> alice.getOutput().contains("[P2P RECEIVED]") && admin1.getOutput().contains("file-for-alice.txt completed"),
                "Alice successfully received file from admin1");

            // Bob accepts
            bob.command("/accept 1");
            await(() -> bob.getOutput().contains("[P2P RECEIVED]") && admin1.getOutput().contains("file-for-bob.txt completed"),
                "Bob successfully received file from admin1");

            // 9. Receive file offers from multiple peers
            System.out.println("\n--- [Step 9: Receive file offers from multiple peers] ---");
            Path fileFromAlice = Path.of("from-alice.txt").toAbsolutePath();
            Files.writeString(fileFromAlice, "Content from Alice");
            Path fileFromBob = Path.of("from-bob.txt").toAbsolutePath();
            Files.writeString(fileFromBob, "Content from Bob");

            int admin1OfferOffset = admin1.getOutput().length();
            alice.command("/sendfile admin1 " + fileFromAlice);
            await(() -> admin1.getOutput().substring(admin1OfferOffset).contains("[FILE OFFER #1]"),
                "admin1 received offer #1 from Alice");

            bob.command("/sendfile admin1 " + fileFromBob);
            await(() -> admin1.getOutput().substring(admin1OfferOffset).contains("[FILE OFFER #2]"),
                "admin1 received offer #2 from Bob");

            // admin1 accepts offer #1 from Alice and offer #2 from Bob
            admin1.command("/accept 1");
            await(() -> alice.getOutput().contains("from-alice.txt completed"),
                "admin1 successfully received file from Alice");

            admin1.command("/accept 2");
            await(() -> bob.getOutput().contains("from-bob.txt completed"),
                "admin1 successfully received file from Bob");

            // 10. Disconnect Alice and verify Bob remains connected
            System.out.println("\n--- [Step 10: Disconnect Alice and verify Bob remains connected] ---");
            alice.close();
            await(() -> !admin1.getClient().getPeerRegistry().isConnected("alice"),
                "Alice disconnected from admin1");
            check(admin1.getClient().getPeerRegistry().isConnected("bob"),
                "Bob remains connected to admin1 after Alice disconnects");

            admin1.command("/msg bob still-alive-after-alice-disconnect");
            await(() -> bob.hasReceived("admin1: still-alive-after-alice-disconnect"),
                "admin1 can still message Bob after Alice disconnects");

            // 11 (part 2). Verify /peers reflects the correct connection state
            System.out.println("\n--- [Step 11 (part 2): Verify /peers reflects only Bob] ---");
            int offsetPeers2 = admin1.getOutput().length();
            admin1.command("/peers");
            await(() -> {
                String out = admin1.getOutput().substring(offsetPeers2);
                return out.contains("- bob (CONNECTED)") && !out.contains("- alice");
            }, "/peers reflects only Bob connected");

            // 12. Verify logout closes all peer connections
            System.out.println("\n--- [Step 12: Verify logout closes all peer connections] ---");
            admin1.close();
            check(admin1.getClient().getPeerRegistry().size() == 0,
                "admin1's peer registry is empty after logout");
            await(() -> !bob.getClient().getPeerRegistry().isConnected("admin1"),
                "Bob's connection to admin1 is closed after admin1 logs out");

            // Cleanup test files
            Files.deleteIfExists(fileForAlice);
            Files.deleteIfExists(fileForBob);
            Files.deleteIfExists(fileFromAlice);
            Files.deleteIfExists(fileFromBob);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("CHECK FAILED: " + message);
        }
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("TIMED OUT: " + message);
            }
            Thread.sleep(15);
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

        @Override public void close() throws Exception {
            listener.close();
            acceptor.join(1000);
            for (Socket socket : sockets) {
                try { socket.close(); } catch (IOException ignored) {}
            }
            for (Thread thread : handlers) thread.join(1000);
        }
    }

    private static class TestClient implements AutoCloseable {
        final PipedWriter console = new PipedWriter();
        final ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        final Client client = new Client();
        final List<String> receivedMessages = new CopyOnWriteArrayList<>();
        final Thread thread;
        final String username;

        TestClient(int port, String username, String password) throws IOException {
            this.username = username;
            client.setChatListener(receivedMessages::add);
            PipedReader input = new PipedReader(console);
            thread = Thread.startVirtualThread(() -> {
                try (Scanner scanner = new Scanner(input)) {
                    client.connectToServer("127.0.0.1", port, scanner);
                }
            });
            command(username);
            command(password);
        }

        boolean hasReceived(String text) {
            return receivedMessages.stream().anyMatch(m -> m.contains(text));
        }

        Client getClient() {
            return client;
        }

        String getOutput() {
            return transcript.toString(StandardCharsets.UTF_8);
        }

        void command(String command) throws IOException {
            console.write(command + "\n");
            console.flush();
        }

        @Override public void close() throws Exception {
            if (thread.isAlive()) {
                command("/logout");
                thread.join(3000);
            }
            console.close();
        }
    }
}
