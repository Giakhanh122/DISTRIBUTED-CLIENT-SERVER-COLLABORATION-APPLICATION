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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ui.model.CommunityModel;
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

    private final Consumer<String> capturedLineHandler = this::handleCapturedLine;
    private final List<ClientEventListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, FileOfferModel> fileOffers = new ConcurrentHashMap<>();

    private Client client;
    private PipedOutputStream commandPipeOut;
    private PrintWriter commandWriter;
    private Thread clientThread;

    private volatile String currentUsername;
    private volatile String selectedPeer;
    private volatile String activeCommunityId;
    private volatile String activeCommunityName;
    private volatile String serverHost;
    private volatile int serverPort;
    private volatile boolean connected = false;
    private volatile boolean loggingIn = false;

    // Line parser state per adapter
    private boolean collectingUserList = false;
    private final List<String> userListBuffer = new ArrayList<>();
    private boolean collectingCommunityList = false;
    private final List<CommunityModel> communityListBuffer = new ArrayList<>();

    private String pendingOfferNum = null;
    private String pendingOfferSender = null;
    private String pendingOfferFile = null;
    private long pendingOfferSize = 0;
    private String pendingOfferCommId = null;
    private boolean pendingIsBroadcast = false;

    private ScheduledExecutorService pollerExecutor;
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private final AtomicBoolean commFlushScheduled = new AtomicBoolean(false);
    private final AtomicLong lastUserListReqTime = new AtomicLong(0);
    private final AtomicLong lastCommListReqTime = new AtomicLong(0);
    private volatile long lastUserListLineTime = 0;
    private volatile long lastCommListLineTime = 0;
    private final List<String> lastKnownOnlineUsers = new CopyOnWriteArrayList<>();
    private final List<CommunityModel> lastKnownCommunities = new CopyOnWriteArrayList<>();

    private void scheduleUserListFlush() {
        lastUserListLineTime = System.currentTimeMillis();
        if (flushScheduled.compareAndSet(false, true)) {
            CompletableFuture.delayedExecutor(60, TimeUnit.MILLISECONDS).execute(() -> {
                synchronized (ChatClientAdapter.this) {
                    flushScheduled.set(false);
                    if (collectingUserList || !userListBuffer.isEmpty()) {
                        flushUserList();
                    }
                }
            });
        }
    }

    private void scheduleCommunityListFlush() {
        lastCommListLineTime = System.currentTimeMillis();
        if (commFlushScheduled.compareAndSet(false, true)) {
            CompletableFuture.delayedExecutor(60, TimeUnit.MILLISECONDS).execute(() -> {
                synchronized (ChatClientAdapter.this) {
                    commFlushScheduled.set(false);
                    if (collectingCommunityList || !communityListBuffer.isEmpty()) {
                        flushCommunityList();
                    }
                }
            });
        }
    }

    public void addListener(ClientEventListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(ClientEventListener listener) {
        listeners.remove(listener);
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public boolean isConnected() {
        return connected;
    }

    public String getActiveCommunityId() {
        return activeCommunityId;
    }

    public String getActiveCommunityName() {
        return activeCommunityName;
    }

    public List<String> getLastKnownOnlineUsers() {
        return Collections.unmodifiableList(lastKnownOnlineUsers);
    }

    public List<CommunityModel> getLastKnownCommunities() {
        return Collections.unmodifiableList(lastKnownCommunities);
    }

    public FileOfferModel getFileOffer(String offerNumber) {
        return fileOffers.get(offerNumber);
    }

    public void discoverServer(Consumer<Client.ServerInfo> callback) {
        Thread.ofVirtual().name("Server-Discovery").start(() -> {
            try {
                Client tempClient = new Client();
                Client.ServerInfo info = tempClient.discover();
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

        interceptSystemStreams();
        if (!globalLineListeners.contains(capturedLineHandler)) {
            globalLineListeners.add(capturedLineHandler);
        }

        try {
            client = new Client();
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
                    stopPolling();
                    notifyConnectionClosed();
                }
            });

        } catch (IOException e) {
            loggingIn = false;
            notifyLoginFailed("Failed to initialize communication stream: " + e.getMessage());
        }
    }

    public void requestUserList() {
        long now = System.currentTimeMillis();
        if (now - lastUserListReqTime.get() < 700) {
            return;
        }
        lastUserListReqTime.set(now);
        if (commandWriter != null && connected) {
            commandWriter.println("/users");
        }
    }

    public void requestCommunityList() {
        long now = System.currentTimeMillis();
        if (now - lastCommListReqTime.get() < 700) {
            return;
        }
        lastCommListReqTime.set(now);
        if (commandWriter != null && connected) {
            commandWriter.println("/communities");
        }
    }

    public void createCommunity(String name) {
        if (commandWriter != null && connected && name != null && !name.isBlank()) {
            commandWriter.println("/community create " + name.trim());
        }
    }

    public void joinCommunity(String communityId) {
        if (commandWriter != null && connected && communityId != null && !communityId.isBlank()) {
            commandWriter.println("/community join " + communityId.trim());
        }
    }

    public void selectCommunity(String communityId) {
        if (commandWriter != null && connected && communityId != null && !communityId.isBlank()) {
            commandWriter.println("/community select " + communityId.trim());
        }
    }

    public void leaveCommunity(String communityId) {
        if (commandWriter != null && connected) {
            if (communityId != null && !communityId.isBlank()) {
                commandWriter.println("/community leave " + communityId.trim());
            } else {
                commandWriter.println("/community leave");
            }
        }
    }

    public void leaveCommunity() {
        leaveCommunity(null);
    }

    public void broadcastMessage(String text) {
        if (commandWriter != null && connected && text != null && !text.isBlank()) {
            commandWriter.println("/broadcast -m " + text.trim());
        }
    }

    public void broadcastFile(File file) {
        if (commandWriter != null && connected && file != null && file.exists()) {
            commandWriter.println("/broadcast -f " + file.getAbsolutePath());
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
        disconnect();
    }

    public synchronized void disconnect() {
        stopPolling();
        globalLineListeners.remove(capturedLineHandler);
        connected = false;
        loggingIn = false;

        if (commandWriter != null) {
            try {
                commandWriter.close();
            } catch (Exception ignored) {}
            commandWriter = null;
        }

        if (commandPipeOut != null) {
            try {
                commandPipeOut.close();
            } catch (Exception ignored) {}
            commandPipeOut = null;
        }

        if (clientThread != null && clientThread.isAlive()) {
            clientThread.interrupt();
            clientThread = null;
        }

        fileOffers.clear();
        selectedPeer = null;
        currentUsername = null;
        activeCommunityId = null;
        activeCommunityName = null;
        lastKnownCommunities.clear();
        lastKnownOnlineUsers.clear();
        communityListBuffer.clear();
        userListBuffer.clear();
    }

    private void startPolling() {
        stopPolling();
        pollerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Poller-" + (currentUsername != null ? currentUsername : "user"));
            t.setDaemon(true);
            return t;
        });

        pollerExecutor.scheduleWithFixedDelay(() -> {
            if (connected) {
                requestUserList();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}
                requestCommunityList();
            }
        }, 3, 4, TimeUnit.SECONDS);
    }

    private void stopPolling() {
        if (pollerExecutor != null && !pollerExecutor.isShutdown()) {
            pollerExecutor.shutdownNow();
            pollerExecutor = null;
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
                startPolling();
                JavaFxUtils.runOnFxThread(() -> {
                    for (ClientEventListener l : listeners) {
                        l.onLoginSuccess(currentUsername);
                    }
                });
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
            if (collectingCommunityList) {
                flushCommunityList();
            }
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

        // Communities List Parsing
        // Format printed in Client.java: "  <name> | ID: <id> | Owner: <owner> | Members: <count> [JOINED]"
        if (trimmed.equals("Communities:")) {
            if (collectingUserList) {
                flushUserList();
            }
            collectingCommunityList = true;
            communityListBuffer.clear();
            scheduleCommunityListFlush();
            return;
        }

        if (trimmed.contains("No communities available")) {
            collectingCommunityList = false;
            communityListBuffer.clear();
            flushCommunityList();
            return;
        }

        if (trimmed.contains(" | ID: ") && trimmed.contains(" | Owner: ") && trimmed.contains(" | Members: ")) {
            Pattern commPattern = Pattern.compile("^\\s*(.*?)\\s*\\|\\s*ID:\\s*(\\S+)\\s*\\|\\s*Owner:\\s*(\\S+)\\s*\\|\\s*Members:\\s*(\\d+)(\\s*\\[JOINED\\])?$");
            Matcher m = commPattern.matcher(trimmed);
            if (m.find()) {
                String name = m.group(1).trim();
                String id = m.group(2).trim();
                String owner = m.group(3).trim();
                int count = Integer.parseInt(m.group(4).trim());
                boolean joined = m.group(5) != null;
                communityListBuffer.removeIf(c -> c.getId().equals(id));
                communityListBuffer.add(new CommunityModel(id, name, owner, count, joined));
                scheduleCommunityListFlush();
                return;
            }
        }

        // Community selection/creation/join events
        // "[Community: Name] selected."
        if (trimmed.contains("[Community: ") && trimmed.contains("] selected.")) {
            int start = trimmed.indexOf("[Community: ") + "[Community: ".length();
            int end = trimmed.indexOf("] selected.");
            String commName = trimmed.substring(start, end).trim();
            this.activeCommunityName = commName;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onCommunityJoined(activeCommunityId != null ? activeCommunityId : commName, commName);
                }
            });
            requestCommunityList();
            return;
        }

        // "Community 'Name' created! ID: <id>"
        if (trimmed.startsWith("Community '") && trimmed.contains("' created! ID: ")) {
            int startName = 11;
            int endName = trimmed.indexOf("' created! ID: ");
            String commName = trimmed.substring(startName, endName);
            String id = trimmed.substring(endName + "' created! ID: ".length()).trim();
            this.activeCommunityId = id;
            this.activeCommunityName = commName;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onCommunityJoined(id, commName);
                }
            });
            requestCommunityList();
            return;
        }

        // "Joined community 'Name'!"
        if (trimmed.startsWith("Joined community '") && trimmed.endsWith("'!")) {
            String commName = trimmed.substring(18, trimmed.length() - 2);
            this.activeCommunityName = commName;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onCommunityJoined(activeCommunityId != null ? activeCommunityId : commName, commName);
                }
            });
            requestCommunityList();
            return;
        }

        // "Left community 'Name'."
        if (trimmed.startsWith("Left community '")) {
            String leftId = activeCommunityId;
            this.activeCommunityId = null;
            this.activeCommunityName = null;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onCommunityLeft(leftId);
                }
            });
            requestCommunityList();
            return;
        }

        // Broadcast Message Parsing
        // Format: "<activeCommunityId>\n(<activeCommunityName>:broadcast) <sender> : <content>"
        if (trimmed.contains(":broadcast) ")) {
            int bcIdx = trimmed.indexOf(":broadcast) ");
            String afterBc = trimmed.substring(bcIdx + ":broadcast) ".length());
            int colon = afterBc.indexOf(" : ");
            if (colon > 0) {
                String sender = afterBc.substring(0, colon).trim();
                String content = afterBc.substring(colon + 3);
                JavaFxUtils.runOnFxThread(() -> {
                    for (ClientEventListener l : listeners) {
                        l.onBroadcastMessageReceived(activeCommunityId, sender, content);
                    }
                });
                return;
            }
        }

        // Broadcast File Offer Parsing
        // [BROADCAST FILE OFFER #1] alice shared 'video.mp4' (12345 bytes) in community [commId:commName].
        Pattern bcOfferPattern = Pattern.compile("\\[BROADCAST FILE OFFER #(\\d+)\\]\\s+(\\w+)\\s+shared '([^']+)'\\s+\\((\\d+)\\s+bytes\\)(?:\\s+in community\\s+\\[([^:]+):([^\\]]*)\\])?.*");
        Matcher bcOfferMatcher = bcOfferPattern.matcher(trimmed);
        if (bcOfferMatcher.find()) {
            pendingOfferNum = bcOfferMatcher.group(1);
            pendingOfferSender = bcOfferMatcher.group(2);
            pendingOfferFile = bcOfferMatcher.group(3);
            pendingOfferSize = Long.parseLong(bcOfferMatcher.group(4));
            pendingOfferCommId = bcOfferMatcher.group(5);
            pendingIsBroadcast = true;
            return;
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
            pendingOfferCommId = null;
            pendingIsBroadcast = false;
            return;
        }

        if (trimmed.startsWith("SHA-256: ") && pendingOfferNum != null) {
            String checksum = trimmed.substring("SHA-256: ".length()).trim();
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
                offer.setBroadcast(pendingIsBroadcast);
                if (pendingOfferCommId != null && !pendingOfferCommId.isBlank()) {
                    offer.setCommunityId(pendingOfferCommId);
                }
                fileOffers.put(pendingOfferNum, offer);

                JavaFxUtils.runOnFxThread(() -> {
                    for (ClientEventListener l : listeners) {
                        l.onFileOfferReceived(offer);
                    }
                });
            }
            pendingOfferNum = null;
            pendingOfferCommId = null;
            pendingIsBroadcast = false;
            return;
        }

        // Outbound offer sent notification
        Pattern outOfferPattern = Pattern.compile("\\[FILE\\]\\s+Offer sent to\\s+(\\w+):\\s+'([^']+)'.*");
        Matcher outOfferMatcher = outOfferPattern.matcher(trimmed);
        if (outOfferMatcher.find()) {
            // Outbound card was already created by handleAttachFile with actual file size.
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

        if (trimmed.startsWith("[FILE] Accepted offer #") || trimmed.startsWith("[BROADCAST] Rejected broadcast file offer #")) {
            String msg = trimmed;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onFileOfferStateChanged("", msg);
                }
            });
            return;
        }

        // File Transfer Completion (P2P or Broadcast)
        if (trimmed.startsWith("[P2P SENT] ") || trimmed.startsWith("[BROADCAST] File '")) {
            String fileName = trimmed;
            if (trimmed.startsWith("[P2P SENT] ")) {
                String rest = trimmed.substring("[P2P SENT] ".length());
                fileName = rest.contains(" completed!") ? rest.substring(0, rest.indexOf(" completed!")) : rest;
            } else if (trimmed.startsWith("[BROADCAST] File '")) {
                int start = "[BROADCAST] File '".length();
                int end = trimmed.indexOf("'", start);
                if (end > start) fileName = trimmed.substring(start, end);
            }
            String finalFile = fileName;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onFileTransferCompleted(finalFile, trimmed, true);
                }
            });
            return;
        }

        if (trimmed.startsWith("[P2P RECEIVED] Saved to: ") || trimmed.startsWith("[BROADCAST RECEIVED] Saved to: ")) {
            String prefix = trimmed.startsWith("[P2P RECEIVED] Saved to: ") ? "[P2P RECEIVED] Saved to: " : "[BROADCAST RECEIVED] Saved to: ";
            String path = trimmed.substring(prefix.length()).trim();
            File f = new File(path);
            String fileName = f.getName();
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onFileTransferCompleted(fileName, path, false);
                }
            });
            return;
        }

        if (trimmed.startsWith("[P2P SEND ERROR] ") || trimmed.startsWith("[P2P RECEIVE ERROR] ")
                || trimmed.startsWith("[BROADCAST DOWNLOAD ERROR] ") || trimmed.startsWith("[BROADCAST] Reject failed")) {
            String err = trimmed;
            JavaFxUtils.runOnFxThread(() -> {
                for (ClientEventListener l : listeners) {
                    l.onFileTransferError(err);
                }
            });
            return;
        }

        // Incoming Direct Chat Message: "<peerUsername>: <message>"
        if (!trimmed.startsWith("Commands:") && !trimmed.startsWith("Finding server:")
                && !trimmed.startsWith("Handshake successful") && !trimmed.startsWith("Server found:")
                && !trimmed.startsWith("Type '/accept") && !trimmed.contains("error:")
                && !trimmed.startsWith("Usage:") && !trimmed.startsWith("PASS:")
                && !trimmed.startsWith("FAIL:") && !trimmed.startsWith("===")
                && !trimmed.startsWith("Username:") && !trimmed.startsWith("Password:")
                && !trimmed.startsWith("[FILE") && !trimmed.startsWith("[P2P") && !trimmed.startsWith("[BROADCAST")
                && !trimmed.startsWith("Online users:") && !trimmed.startsWith("Peer ")
                && !trimmed.startsWith("Now chatting with") && !trimmed.startsWith("Cannot ")
                && !trimmed.startsWith("Communities:")) {

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

        if (list.equals(lastKnownOnlineUsers)) {
            return;
        }

        lastKnownOnlineUsers.clear();
        lastKnownOnlineUsers.addAll(list);
        JavaFxUtils.runOnFxThread(() -> {
            for (ClientEventListener l : listeners) {
                l.onUserListUpdated(list);
            }
        });
    }

    private synchronized void flushCommunityList() {
        collectingCommunityList = false;
        commFlushScheduled.set(false);
        List<CommunityModel> list = new ArrayList<>(communityListBuffer);
        communityListBuffer.clear();

        if (list.equals(lastKnownCommunities)) {
            return;
        }

        lastKnownCommunities.clear();
        lastKnownCommunities.addAll(list);
        JavaFxUtils.runOnFxThread(() -> {
            for (ClientEventListener l : listeners) {
                l.onCommunityListUpdated(list);
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

    private static class LineSplittingOutputStream extends OutputStream {
        private final OutputStream downstream;
        private final Consumer<String> lineConsumer;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);

        LineSplittingOutputStream(OutputStream downstream, Consumer<String> lineConsumer) {
            this.downstream = downstream;
            this.lineConsumer = lineConsumer;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            downstream.write(b);
            if (b == '\n') {
                byte[] bytes = buffer.toByteArray();
                buffer.reset();
                String line = new String(bytes, StandardCharsets.UTF_8).replace("\r", "");
                lineConsumer.accept(line);
            } else if (b != '\r') {
                buffer.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            downstream.write(b, off, len);
            for (int i = off; i < off + len; i++) {
                byte val = b[i];
                if (val == '\n') {
                    byte[] bytes = buffer.toByteArray();
                    buffer.reset();
                    String line = new String(bytes, StandardCharsets.UTF_8).replace("\r", "");
                    lineConsumer.accept(line);
                } else if (val != '\r') {
                    buffer.write(val);
                }
            }
        }

        @Override
        public void flush() throws IOException {
            downstream.flush();
        }

        @Override
        public void close() throws IOException {
            downstream.close();
        }
    }
}
