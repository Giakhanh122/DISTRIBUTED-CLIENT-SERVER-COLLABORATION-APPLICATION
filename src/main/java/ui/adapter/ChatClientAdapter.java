package ui.adapter;

import client.Client;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ui.model.FileOfferModel;
import ui.util.JavaFxUtils;

public class ChatClientAdapter {

    // Global line listeners so any active adapter receives captured stdout/stderr
    private static final List<Consumer<String>> globalLineListeners = new CopyOnWriteArrayList<>();
    private static volatile boolean streamsIntercepted = false;

    private static final ChatClientAdapter INSTANCE = new ChatClientAdapter();

    public static ChatClientAdapter getInstance() {
        return INSTANCE;
    }

    private static synchronized void interceptSystemStreams() {
        if (streamsIntercepted) return;
        streamsIntercepted = true;

        PrintStream origOut = System.out;
        PrintStream origErr = System.err;

        System.setOut(new PrintStream(new LineSplittingOutputStream(origOut, line -> {
            for (Consumer<String> l : globalLineListeners) {
                l.accept(line);
            }
        }), true, StandardCharsets.UTF_8));

        System.setErr(new PrintStream(new LineSplittingOutputStream(origErr, line -> {
            for (Consumer<String> l : globalLineListeners) {
                l.accept(line);
            }
        }), true, StandardCharsets.UTF_8));
    }

    private final List<ClientEventListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, FileOfferModel> fileOffers = new ConcurrentHashMap<>();

    private Client client;
    private PipedOutputStream commandPipeOut;
    private PrintWriter commandWriter;
    private Thread clientThread;

    private volatile String currentUsername;
    private volatile String selectedPeer;
    private volatile String serverHost;
    private volatile int serverPort;
    private volatile boolean connected = false;
    private volatile boolean loggingIn = false;

    // Line parser state per adapter
    private boolean collectingUserList = false;
    private final List<String> userListBuffer = new ArrayList<>();
    private String pendingOfferNum = null;
    private String pendingOfferSender = null;
    private String pendingOfferFile = null;
    private long pendingOfferSize = 0;

    private ScheduledExecutorService userListPollExecutor;
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private volatile long lastUserListLineTime = 0;
    private final List<String> lastKnownOnlineUsers = new CopyOnWriteArrayList<>();

    private void scheduleUserListFlush() {
        lastUserListLineTime = System.currentTimeMillis();
        if (flushScheduled.compareAndSet(false, true)) {
            CompletableFuture.delayedExecutor(150, TimeUnit.MILLISECONDS).execute(() -> {
                synchronized (ChatClientAdapter.this) {
                    flushScheduled.set(false);
                    long elapsed = System.currentTimeMillis() - lastUserListLineTime;
                    if (elapsed < 120) {
                        scheduleUserListFlush();
                    } else if (collectingUserList || !userListBuffer.isEmpty()) {
                        flushUserList();
                    }
                }
            });
        }
    }

    public List<String> getLastKnownOnlineUsers() {
        return Collections.unmodifiableList(lastKnownOnlineUsers);
    }

    public ChatClientAdapter() {
        interceptSystemStreams();
        globalLineListeners.add(this::handleCapturedLine);
    }

    public void addListener(ClientEventListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(ClientEventListener listener) {
        listeners.remove(listener);
    }

    public synchronized void discoverServer(Consumer<Client.ServerInfo> callback) {
        Thread.startVirtualThread(() -> {
            try {
                Client c = new Client();
                Client.ServerInfo info = c.discover();
                JavaFxUtils.runOnFxThread(() -> callback.accept(info));
            } catch (Exception e) {
                JavaFxUtils.runOnFxThread(() -> callback.accept(null));
            }
        });
    }

    public synchronized void login(String host, int port, String username, String password) {
        if (connected || loggingIn) {
            return;
        }
        this.serverHost = host;
        this.serverPort = port;
        this.currentUsername = username;
        this.loggingIn = true;
        this.client = new Client();

        try {
            commandPipeOut = new PipedOutputStream();
            PipedInputStream commandPipeIn = new PipedInputStream(commandPipeOut, 8192);
            commandWriter = new PrintWriter(commandPipeOut, true, StandardCharsets.UTF_8);
            Scanner scanner = new Scanner(commandPipeIn, StandardCharsets.UTF_8);

            clientThread = Thread.ofVirtual().name("Client-Runner-" + username).start(() -> {
                try {
                    // Feed login credentials
                    commandWriter.println(username);
                    commandWriter.println(password);

                    client.connectToServer(host, port, scanner);
                } catch (Exception e) {
                    notifySystem("Connection failed: " + e.getMessage(), true);
                    notifyLoginFailed(e.getMessage());
                } finally {
                    connected = false;
                    loggingIn = false;
                    stopUserListPolling();
                    notifyConnectionClosed();
                }
            });

        } catch (IOException e) {
            loggingIn = false;
            notifyLoginFailed("Failed to initialize communication stream: " + e.getMessage());
        }
    }

    public void requestUserList() {
        if (commandWriter != null && connected) {
            commandWriter.println("/users");
        }
    }

    public void selectPeer(String peerUsername) {
        if (peerUsername == null || peerUsername.isBlank() || peerUsername.equals(currentUsername)) {
            return;
        }
        this.selectedPeer = peerUsername;
        if (commandWriter != null && connected) {
            commandWriter.println("/lookup " + peerUsername);
        }
    }

    public void sendChatMessage(String targetPeer, String text) {
        if (commandWriter == null || !connected) {
            notifySystem("Not connected to server", true);
            return;
        }
        if (text == null || text.isBlank()) {
            return;
        }

        if (!targetPeer.equals(selectedPeer)) {
            this.selectedPeer = targetPeer;
            commandWriter.println("/lookup " + targetPeer);
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
        }

        commandWriter.println(text);
    }

    public void sendFile(String targetPeer, File file) {
        if (commandWriter == null || !connected) {
            notifySystem("Not connected to server", true);
            return;
        }
        if (file == null || !file.exists()) {
            notifySystem("File does not exist", true);
            return;
        }

        if (!targetPeer.equals(selectedPeer)) {
            this.selectedPeer = targetPeer;
            commandWriter.println("/lookup " + targetPeer);
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
        }

        commandWriter.println("/sendfile " + targetPeer + " " + file.getAbsolutePath());
    }

    public void acceptFileOffer(String offerNumber) {
        if (commandWriter != null && connected) {
            commandWriter.println("/accept " + offerNumber);
        }
    }

    public void rejectFileOffer(String offerNumber) {
        if (commandWriter != null && connected) {
            commandWriter.println("/reject " + offerNumber);
        }
    }

    public synchronized void logout() {
        if (commandWriter != null && connected) {
            commandWriter.println("/logout");
        }
        connected = false;
        loggingIn = false;
        stopUserListPolling();
    }

    public void disconnect() {
        logout();
        if (commandWriter != null) {
            try {
                commandPipeOut.close();
            } catch (IOException ignored) {}
        }
        if (clientThread != null) {
            clientThread.interrupt();
        }
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    public String getSelectedPeer() {
        return selectedPeer;
    }

    public boolean isConnected() {
        return connected;
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public FileOfferModel getFileOffer(String offerNumber) {
        return fileOffers.get(offerNumber);
    }

    private void startUserListPolling() {
        stopUserListPolling();
        userListPollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "UserList-Poller-" + currentUsername);
            t.setDaemon(true);
            return t;
        });
        userListPollExecutor.scheduleWithFixedDelay(() -> {
            if (connected) {
                requestUserList();
            }
        }, 1, 3, TimeUnit.SECONDS);
    }

    private void stopUserListPolling() {
        if (userListPollExecutor != null && !userListPollExecutor.isShutdown()) {
            userListPollExecutor.shutdownNow();
            userListPollExecutor = null;
        }
    }

    // --- Stream Output Handling ---

    private synchronized void handleCapturedLine(String line) {
        if (line == null) return;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return;

        // Login events
        if (trimmed.contains("Login successful!")) {
            if (loggingIn) {
                connected = true;
                loggingIn = false;
                startUserListPolling();
                JavaFxUtils.runOnFxThread(() -> {
                    for (ClientEventListener l : listeners) {
                        l.onLoginSuccess(currentUsername);
                    }
                });
                requestUserList();
            }
            return;
        }

        if (trimmed.contains("Login failed:") || trimmed.contains("Invalid or missing AUTH_RESPONSE")
                || trimmed.contains("Username and password must not be empty")) {
            if (loggingIn) {
                connected = false;
                loggingIn = false;
                String reason = trimmed;
                JavaFxUtils.runOnFxThread(() -> {
                    for (ClientEventListener l : listeners) {
                        l.onLoginFailed(reason);
                    }
                });
            }
            return;
        }

        // Online Users List Parsing
        if (trimmed.contains("Online users:")) {
            collectingUserList = true;
            userListBuffer.clear();
            scheduleUserListFlush();
            return;
        }

        if (collectingUserList) {
            if (trimmed.startsWith("- ")) {
                String u = trimmed.substring(2).trim();
                if (!u.isEmpty()) {
                    userListBuffer.add(u);
                }
                scheduleUserListFlush();
                return;
            } else if (trimmed.equals("(none)")) {
                flushUserList();
                return;
            } else {
                flushUserList();
            }
        }

        // Peer Lookup & Connection Events
        if (trimmed.contains("Now chatting with ")) {
            int idx = trimmed.indexOf("Now chatting with ");
            String peer = trimmed.substring(idx + "Now chatting with ".length()).trim();
            this.selectedPeer = peer;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onPeerConnected(peer);
                }
            });
            return;
        }

        if (trimmed.contains(" is offline")) {
            int idx = trimmed.indexOf(" is offline");
            String peer = trimmed.substring(0, idx).trim();
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onPeerOffline(peer);
                }
            });
            return;
        }

        if (trimmed.contains("Cannot connect to ")) {
            String detail = trimmed;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onPeerConnectionFailed(selectedPeer, detail);
                }
            });
            return;
        }

        if (trimmed.contains("Peer handshake successful with ")) {
            int idx = trimmed.indexOf("Peer handshake successful with ");
            String peer = trimmed.substring(idx + "Peer handshake successful with ".length()).trim();
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onPeerConnected(peer);
                }
            });
            return;
        }

        // File Offer Parsing
        // [FILE OFFER #1] alice wants to send 'photo.png' (12345 bytes).
        Pattern offerPattern = Pattern.compile("\\[FILE OFFER #(\\d+)\\]\\s+(\\w+)\\s+wants to send '([^']+)'\\s+\\((\\d+)\\s+bytes\\)\\.");
        Matcher offerMatcher = offerPattern.matcher(trimmed);
        if (offerMatcher.find()) {
            pendingOfferNum = offerMatcher.group(1);
            pendingOfferSender = offerMatcher.group(2);
            pendingOfferFile = offerMatcher.group(3);
            pendingOfferSize = Long.parseLong(offerMatcher.group(4));
            return;
        }

        if (trimmed.startsWith("SHA-256: ") && pendingOfferNum != null) {
            String checksum = trimmed.substring("SHA-256: ".length()).trim();
            // Ensure this offer is intended for this receiver
            if (currentUsername != null && !currentUsername.equals(pendingOfferSender)) {
                FileOfferModel offer = new FileOfferModel(
                        pendingOfferNum,
                        pendingOfferSender,
                        currentUsername,
                        pendingOfferFile,
                        pendingOfferSize,
                        checksum,
                        true
                );
                fileOffers.put(pendingOfferNum, offer);

                JavaFxUtils.runOnFxThread(() -> {
                    for (ClientEventListener l : listeners) {
                        l.onFileOfferReceived(offer);
                    }
                });
            }
            pendingOfferNum = null;
            return;
        }

        // Outbound offer sent: "[FILE] Offer sent to bob: 'data.zip'."
        Pattern outOfferPattern = Pattern.compile("\\[FILE\\]\\s+Offer sent to\\s+(\\w+):\\s+'([^']+)'.*");
        Matcher outOfferMatcher = outOfferPattern.matcher(trimmed);
        if (outOfferMatcher.find()) {
            String receiver = outOfferMatcher.group(1);
            String fileName = outOfferMatcher.group(2);
            // Only the sender gets the outbound notification
            if (currentUsername != null && !currentUsername.equals(receiver)) {
                FileOfferModel offer = new FileOfferModel(
                        "out-" + System.currentTimeMillis(),
                        currentUsername,
                        receiver,
                        fileName,
                        0,
                        "",
                        false
                );
                JavaFxUtils.runOnFxThread(() -> {
                    for (ClientEventListener l : listeners) {
                        l.onFileOfferReceived(offer);
                    }
                });
            }
            return;
        }

        // File Decision: Accepted / Rejected
        Pattern decisionPattern = Pattern.compile("\\[FILE\\]\\s+Transfer\\s+'([^']+)'\\s+was\\s+(ACCEPTED|REJECTED)\\s+by\\s+(\\w+)\\.");
        Matcher decisionMatcher = decisionPattern.matcher(trimmed);
        if (decisionMatcher.find()) {
            String fileName = decisionMatcher.group(1);
            String action = decisionMatcher.group(2);
            String peer = decisionMatcher.group(3);
            String msg = "Transfer '" + fileName + "' was " + action + " by " + peer;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onFileOfferStateChanged(fileName, msg);
                }
            });
            return;
        }

        if (trimmed.startsWith("[FILE] Accepted offer #")) {
            String msg = trimmed;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onFileOfferStateChanged("", msg);
                }
            });
            return;
        }

        if (trimmed.startsWith("[FILE] Rejected offer #")) {
            String msg = trimmed;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onFileOfferStateChanged("", msg);
                }
            });
            return;
        }

        // File Transfer Completion
        // [P2P SENT] test.txt completed! SHA-256: ...
        if (trimmed.startsWith("[P2P SENT] ")) {
            String rest = trimmed.substring("[P2P SENT] ".length());
            String fileName = rest.contains(" completed!") ? rest.substring(0, rest.indexOf(" completed!")) : rest;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onFileTransferCompleted(fileName, rest, true);
                }
            });
            return;
        }

        // [P2P RECEIVED] Saved to: /path/to/downloads/file
        if (trimmed.startsWith("[P2P RECEIVED] Saved to: ")) {
            String path = trimmed.substring("[P2P RECEIVED] Saved to: ".length()).trim();
            File f = new File(path);
            String fileName = f.getName();
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onFileTransferCompleted(fileName, path, false);
                }
            });
            return;
        }

        if (trimmed.startsWith("[P2P SEND ERROR] ") || trimmed.startsWith("[P2P RECEIVE ERROR] ") || trimmed.startsWith("[FILE OFFER ERROR] ")) {
            String err = trimmed;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onFileTransferError(err);
                }
            });
            return;
        }

        // Incoming Chat Message: "<peerUsername>: <message>"
        if (!trimmed.startsWith("Commands:") && !trimmed.startsWith("Finding server:")
                && !trimmed.startsWith("Handshake successful") && !trimmed.startsWith("Server found:")
                && !trimmed.startsWith("Type '/accept") && !trimmed.contains("error:")
                && !trimmed.startsWith("Usage:") && !trimmed.startsWith("PASS:")
                && !trimmed.startsWith("FAIL:") && !trimmed.startsWith("===")
                && !trimmed.startsWith("Username:") && !trimmed.startsWith("Password:")
                && !trimmed.startsWith("[FILE") && !trimmed.startsWith("[P2P")
                && !trimmed.startsWith("Online users:") && !trimmed.startsWith("Peer ")
                && !trimmed.startsWith("Now chatting with") && !trimmed.startsWith("Cannot ")) {

            int colonIdx = trimmed.indexOf(": ");
            if (colonIdx > 0) {
                String senderCandidate = trimmed.substring(0, colonIdx).trim();
                String content = trimmed.substring(colonIdx + 2);
                if (senderCandidate.matches("^[a-zA-Z0-9_-]+$") && !senderCandidate.equals("SHA-256")
                        && !senderCandidate.equalsIgnoreCase(currentUsername)) {
                    JavaFxUtils.runOnFxThread(() -> {
                        for (ClientEventListener l : listeners) {
                            l.onMessageReceived(senderCandidate, content);
                        }
                    });
                    return;
                }
            }
        }
    }

    private synchronized void flushUserList() {
        collectingUserList = false;
        flushScheduled.set(false);
        List<String> list = new ArrayList<>(userListBuffer);
        userListBuffer.clear();
        lastKnownOnlineUsers.clear();
        lastKnownOnlineUsers.addAll(list);
        JavaFxUtils.runOnFxThread(() -> {
            for (ClientEventListener l : listeners) {
                l.onUserListUpdated(list);
            }
        });
    }

    private void notifyLoginFailed(String reason) {
        JavaFxUtils.runOnFxThread(() -> {
            for (ClientEventListener l : listeners) {
                l.onLoginFailed(reason);
            }
        });
    }

    private void notifyConnectionClosed() {
        JavaFxUtils.runOnFxThread(() -> {
            for (ClientEventListener l : listeners) {
                l.onConnectionClosed();
            }
        });
    }

    private void notifySystem(String message, boolean isError) {
        JavaFxUtils.runOnFxThread(() -> {
            for (ClientEventListener l : listeners) {
                l.onSystemNotification(message, isError);
            }
        });
    }

    // --- Helper LineSplittingOutputStream ---
    private static class LineSplittingOutputStream extends OutputStream {
        private final PrintStream target;
        private final Consumer<String> lineConsumer;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public LineSplittingOutputStream(PrintStream target, Consumer<String> lineConsumer) {
            this.target = target;
            this.lineConsumer = lineConsumer;
        }

        @Override
        public void write(int b) throws IOException {
            String lineToEmit = null;
            synchronized (this) {
                target.write(b);
                if (b == '\n') {
                    target.flush();
                    byte[] bytes = buffer.toByteArray();
                    buffer.reset();
                    lineToEmit = new String(bytes, StandardCharsets.UTF_8);
                } else if (b != '\r') {
                    buffer.write(b);
                }
            }
            if (lineToEmit != null) {
                lineConsumer.accept(lineToEmit);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            List<String> linesToEmit = null;
            synchronized (this) {
                target.write(b, off, len);
                boolean hasNewline = false;
                for (int i = off; i < off + len; i++) {
                    byte val = b[i];
                    if (val == '\n') {
                        hasNewline = true;
                        byte[] bytes = buffer.toByteArray();
                        buffer.reset();
                        if (linesToEmit == null) linesToEmit = new ArrayList<>();
                        linesToEmit.add(new String(bytes, StandardCharsets.UTF_8));
                    } else if (val != '\r') {
                        buffer.write(val);
                    }
                }
                if (hasNewline) {
                    target.flush();
                }
            }
            if (linesToEmit != null) {
                for (String line : linesToEmit) {
                    lineConsumer.accept(line);
                }
            }
        }

        @Override
        public void flush() throws IOException {
            synchronized (this) {
                target.flush();
            }
        }
    }
}
