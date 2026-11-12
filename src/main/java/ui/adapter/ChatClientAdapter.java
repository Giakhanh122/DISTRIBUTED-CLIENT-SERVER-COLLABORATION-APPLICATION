package ui.adapter;

import client.Client;
import client.ClientEvents;
import client.ClientSession;
import client.CommunityClient;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import protocols.FileTransferMeta;
import protocols.Json;
import ui.model.CommunityModel;
import ui.model.FileOfferModel;
import ui.util.JavaFxUtils;

/** Maps session data to JavaFX models; all network operations run off the FX thread. */
public final class ChatClientAdapter {
    private static final ChatClientAdapter INSTANCE = new ChatClientAdapter();
    public static ChatClientAdapter getInstance() { return INSTANCE; }

    @FunctionalInterface
    private interface SessionTask { void run(ClientSession session) throws IOException; }

    private final List<ClientEventListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, FileOfferModel> fileOffers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "UI-Client-Commands");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    private volatile ClientSession session;
    private volatile boolean connected;
    private volatile boolean loggingIn;
    private volatile String currentUsername;
    private volatile String serverHost;
    private volatile int serverPort;
    private volatile String activeCommunityId;
    private volatile String activeCommunityName;
    private volatile List<String> onlineUsers = List.of();
    private volatile List<CommunityModel> communities = List.of();
    private ScheduledFuture<?> poller;
    // Only accessed on the command executor.
    private long commandToken;

    private ChatClientAdapter() {}

    public void addListener(ClientEventListener listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    public void removeListener(ClientEventListener listener) { listeners.remove(listener); }
    public String getCurrentUsername() { return currentUsername; }
    public String getServerHost() { return serverHost; }
    public int getServerPort() { return serverPort; }
    public boolean isConnected() { return connected; }
    public String getActiveCommunityId() { return activeCommunityId; }
    public String getActiveCommunityName() { return activeCommunityName; }
    public List<String> getLastKnownOnlineUsers() { return onlineUsers; }
    public List<CommunityModel> getLastKnownCommunities() { return communities; }
    public FileOfferModel getFileOffer(String id) { return fileOffers.get(id); }

    private void dispatch(long token, Consumer<ClientEventListener> event) {
        JavaFxUtils.runOnFxThread(() -> {
            if (generation.get() == token) listeners.forEach(event);
        });
    }

    public void discoverServer(Consumer<Client.ServerInfo> callback) {
        Thread.startVirtualThread(() -> {
            Client.ServerInfo result;
            try { result = new Client().discover(); } catch (IOException e) { result = null; }
            Client.ServerInfo found = result;
            JavaFxUtils.runOnFxThread(() -> callback.accept(found));
        });
    }

    public synchronized void login(String host, int port, String username, String password) {
        if (connected || loggingIn) return;
        long token = generation.incrementAndGet();
        loggingIn = true;
        if (poller != null) { poller.cancel(false); poller = null; }
        ClientSession previous = session;
        session = null;
        if (previous != null) executor.execute(previous::close);
        currentUsername = username;
        serverHost = host;
        serverPort = port;
        executor.execute(() -> {
            try {
                ClientSession opened = new Client().login(host, port, username, password, events(token));
                synchronized (this) {
                    if (generation.get() != token) { opened.close(); return; }
                    session = opened;
                    connected = true;
                    loggingIn = false;
                    dispatch(token, listener -> listener.onLoginSuccess(username));
                    opened.start();
                    poller = executor.scheduleWithFixedDelay(() -> {
                        if (generation.get() != token || !connected) return;
                        try { refresh(opened, token); }
                        catch (IOException e) { dispatch(token, l -> l.onSystemNotification(e.getMessage(), true)); }
                    }, 0, 3, TimeUnit.SECONDS);
                }
            } catch (IOException | RuntimeException e) {
                if (generation.get() == token) {
                    loggingIn = false;
                    connected = false;
                    dispatch(token, listener -> listener.onLoginFailed(e.getMessage()));
                }
            }
        });
    }

    private void submit(SessionTask task) {
        long token = generation.get();
        ClientSession current = session;
        executor.execute(() -> {
            if (current == null || current != session || generation.get() != token) return;
            commandToken = token;
            try { task.run(current); }
            catch (IOException | RuntimeException e) {
                dispatch(token, l -> l.onSystemNotification(e.getMessage(), true));
            }
        });
    }

    public void requestUserList() { submit(s -> refreshUsers(s, commandToken)); }
    public void requestCommunityList() { submit(s -> refreshCommunities(s, commandToken)); }

    private void refresh(ClientSession current, long token) throws IOException {
        refreshUsers(current, token);
        refreshCommunities(current, token);
    }

    private void refreshUsers(ClientSession current, long token) throws IOException {
        List<String> users = new ArrayList<>();
        for (JsonElement entry : current.users()) users.add(entry.getAsString());
        List<String> snapshot = List.copyOf(users);
        JavaFxUtils.runOnFxThread(() -> {
            if (generation.get() != token || snapshot.equals(onlineUsers)) return;
            onlineUsers = snapshot;
            listeners.forEach(l -> l.onUserListUpdated(snapshot));
        });
    }

    private void refreshCommunities(ClientSession current, long token) throws IOException {
        List<JsonObject> data = new ArrayList<>();
        for (JsonElement entry : current.communities().list()) data.add(Json.asObject(entry));
        JavaFxUtils.runOnFxThread(() -> {
            if (generation.get() != token) return;
            List<CommunityModel> snapshot = new ArrayList<>();
            try {
                for (JsonObject entry : data) snapshot.add(new CommunityModel(Json.string(entry, "communityId"),
                        Json.string(entry, "name"), Json.string(entry, "owner"),
                        Json.integer(entry, "memberCount"), Json.bool(entry, "isMember")));
            } catch (IOException e) {
                listeners.forEach(l -> l.onSystemNotification(e.getMessage(), true));
                return;
            }
            if (snapshot.equals(communities)) return;
            communities = List.copyOf(snapshot);
            listeners.forEach(l -> l.onCommunityListUpdated(communities));
        });
    }

    private void selected(CommunityClient.Selection selection) {
        if (generation.get() != commandToken) return;
        activeCommunityId = selection.id();
        activeCommunityName = selection.name();
        dispatch(commandToken, l -> l.onCommunityJoined(selection.id(), selection.name()));
        requestCommunityList();
    }

    public void createCommunity(String name) { submit(s -> selected(s.communities().create(name.trim()))); }
    public void joinCommunity(String id) { submit(s -> selected(s.communities().join(id))); }
    public void selectCommunity(String id) { submit(s -> selected(s.communities().select(id))); }
    public void leaveCommunity() { leaveCommunity(activeCommunityId); }
    public void leaveCommunity(String id) {
        submit(s -> {
            s.communities().leave(id);
            if (generation.get() != commandToken) return;
            if (id.equals(activeCommunityId)) { activeCommunityId = null; activeCommunityName = null; }
            dispatch(commandToken, l -> l.onCommunityLeft(id));
            requestCommunityList();
        });
    }

    public void selectPeer(String peer) {
        submit(s -> {
            try { s.ensurePeer(peer); dispatch(commandToken, l -> l.onPeerConnected(peer)); }
            catch (IOException e) { dispatch(commandToken, l -> l.onPeerConnectionFailed(peer, e.getMessage())); }
        });
    }
    public void sendChatMessage(String peer, String text) { submit(s -> s.sendMessage(peer, text)); }
    public void sendFile(String peer, File file) { submit(s -> { s.ensurePeer(peer); s.sendFile(peer, file); }); }
    public void broadcastMessage(String text) { submit(s -> s.broadcastMessage(text)); }
    public void broadcastFile(File file) { submit(s -> s.broadcastFile(file)); }
    public void acceptFileOffer(String id) { decideFile(id, true); }
    public void rejectFileOffer(String id) { decideFile(id, false); }
    private void decideFile(String id, boolean accept) {
        submit(s -> {
            try {
                if (accept) s.accept(id); else s.reject(id);
            } catch (IOException | RuntimeException e) {
                events(commandToken).onFileState(id, "FAILED", e.getMessage());
            }
        });
    }

    private ClientEvents events(long token) {
        return new ClientEvents() {
            public void onMessage(String peer, String content) { dispatch(token, l -> l.onMessageReceived(peer, content)); }
            public void onBroadcast(String id, String sender, String content) {
                dispatch(token, l -> l.onBroadcastMessageReceived(id, sender, content));
            }
            public void onPeerConnected(String peer) { dispatch(token, l -> l.onPeerConnected(peer)); }
            public void onPeerDisconnected(String peer) { dispatch(token, l -> l.onPeerDisconnected(peer)); }
            public void onServerClosed() {
                if (generation.get() != token) return;
                connected = false;
                dispatch(token, ClientEventListener::onConnectionClosed);
            }
            public void onFileOffer(String id, FileTransferMeta meta, String communityId, boolean incoming) {
                JavaFxUtils.runOnFxThread(() -> {
                    if (generation.get() != token) return;
                    FileOfferModel model = new FileOfferModel(id, meta.sender(), meta.receiver(), meta.fileName(),
                            meta.fileSize(), meta.checksum(), incoming);
                    model.setBroadcast(communityId != null);
                    if (communityId != null) model.setCommunityId(communityId);
                    fileOffers.put(id, model);
                    listeners.forEach(l -> l.onFileOfferReceived(model));
                });
            }
            public void onFileState(String id, String state, String detail) {
                JavaFxUtils.runOnFxThread(() -> {
                    if (generation.get() != token) return;
                    FileOfferModel model = fileOffers.get(id);
                    if (model == null) {
                        if (state.equals("FAILED")) listeners.forEach(l -> l.onSystemNotification(detail, true));
                        return;
                    }
                    model.setStatus(FileOfferModel.Status.valueOf(state));
                    if (state.equals("COMPLETED")) {
                        model.setLocalPath(detail);
                        model.setProgress(1);
                    } else if (detail != null) model.setStatusText(detail);
                });
            }
        };
    }

    public synchronized void logout() {
        generation.incrementAndGet();
        connected = false;
        loggingIn = false;
        if (poller != null) { poller.cancel(false); poller = null; }
        ClientSession closing = session;
        session = null;
        activeCommunityId = null;
        activeCommunityName = null;
        onlineUsers = List.of();
        communities = List.of();
        fileOffers.clear();
        if (closing != null) executor.execute(closing::close);
    }
}
