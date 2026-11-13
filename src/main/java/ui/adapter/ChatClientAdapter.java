package ui.adapter;

import client.Client;
import client.ClientEvents;
import client.ConnectionState;
import client.FileOffer;
import client.ClientSession;
import client.CommunityClient;
import client.CommunityInfo;
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
    private final ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "UI-Client-Polling");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong connectionRevision = new AtomicLong();
    private volatile ClientSession session;
    private volatile boolean loggingIn;
    // UI synchronization phase; transport lifecycle is owned by ClientSession.
    private volatile boolean refreshing;
    private volatile String currentUsername;
    private volatile List<String> onlineUsers = List.of();
    private volatile List<CommunityModel> communities = List.of();
    private ScheduledFuture<?> poller;
    // Only accessed on the command executor.
    private long commandToken;
    private long commandRevision;

    private ChatClientAdapter() {}

    public void addListener(ClientEventListener listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    public void removeListener(ClientEventListener listener) { listeners.remove(listener); }
    public ConnectionState connectionState() {
        ClientSession current = session;
        if (loggingIn) return ConnectionState.CONNECTING;
        if (current == null) return ConnectionState.CLOSED;
        return refreshing ? ConnectionState.RECONNECTING : current.connectionState();
    }
    public boolean isServerConnected() {
        ClientSession current = session;
        return current != null && current.isServerConnected() && !refreshing;
    }
    public boolean isReconnecting() { return connectionState() == ConnectionState.RECONNECTING; }
    public boolean isPeerConnected(String peer) {
        ClientSession current = session;
        return current != null && current.isPeerConnected(peer);
    }
    public String getCurrentUsername() { return currentUsername; }
    public List<String> getLastKnownOnlineUsers() { return onlineUsers; }
    public List<CommunityModel> getLastKnownCommunities() { return communities; }

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
        if (session != null || loggingIn || refreshing) return;
        long token = generation.incrementAndGet();
        loggingIn = true;
        if (poller != null) { poller.cancel(false); poller = null; }
        currentUsername = username;
        executor.execute(() -> {
            try {
                ClientSession opened = new Client().login(host, port, username, password, events(token));
                synchronized (this) {
                    if (generation.get() != token) { opened.close(); return; }
                    session = opened;
                    opened.start();
                    loggingIn = false;
                    dispatch(token, listener -> listener.onLoginSuccess(username));
                    startPolling(opened, token);
                }
            } catch (IOException | RuntimeException e) {
                synchronized (this) {
                    if (generation.get() == token) {
                        ClientSession failed = session;
                        session = null;
                        if (failed != null) failed.close();
                        loggingIn = false;
                        dispatch(token, listener -> listener.onLoginFailed(e.getMessage()));
                    }
                }
            }
        });
    }

    private void startPolling(ClientSession current, long token) {
        if (poller != null) poller.cancel(false);
        poller = polling.scheduleWithFixedDelay(() -> {
            if (generation.get() != token || !current.isServerConnected()) return;
            try { refresh(current, token); }
            catch (IOException | RuntimeException e) {
                if (current.isServerConnected()) dispatch(token, l -> l.onSystemNotification(e.getMessage(), true));
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    public synchronized void reconnect(String password) {
        ClientSession current = session;
        if (current == null || current.connectionState() != ConnectionState.SERVER_OFFLINE || loggingIn || refreshing) return;
        long token = generation.get();
        refreshing = true;
        dispatch(token, ClientEventListener::onReconnecting);
        executor.execute(() -> {
            try {
                if (current != session || generation.get() != token) return;
                current.reconnect(password);
                synchronized (this) {
                    if (current != session || generation.get() != token) return;
                    if (!current.isServerConnected()) throw new IOException("Server disconnected during reconnect");
                }
                refresh(current, token);
                synchronized (this) {
                    if (current != session || generation.get() != token) return;
                    if (!current.isServerConnected()) throw new IOException("Server disconnected during refresh");
                    refreshing = false;
                    startPolling(current, token);
                    dispatch(token, ClientEventListener::onReconnected);
                }
            } catch (IOException | RuntimeException e) {
                try { current.disconnectServer(); } catch (IOException closeError) { e.addSuppressed(closeError); }
                synchronized (this) {
                    if (current != session || generation.get() != token) return;
                    refreshing = false;
                    dispatch(token, listener -> listener.onReconnectFailed(e.getMessage()));
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

    private void submitServer(SessionTask task) {
        long revision = connectionRevision.get();
        submit(current -> {
            if (!isServerConnected() || revision != connectionRevision.get()) {
                throw new IOException("Server unavailable; operation canceled");
            }
            commandRevision = revision;
            task.run(current);
        });
    }

    private void dispatchServer(Consumer<ClientEventListener> event) {
        long token = commandToken;
        long revision = commandRevision;
        dispatch(token, listener -> {
            if (isServerConnected() && revision == connectionRevision.get()) event.accept(listener);
        });
    }

    public void requestUserList() { if (isServerConnected()) submitServer(s -> refreshUsers(s, commandToken)); }
    public void requestCommunityList() { if (isServerConnected()) submitServer(s -> refreshCommunities(s, commandToken)); }

    private void refresh(ClientSession current, long token) throws IOException {
        refreshUsers(current, token);
        refreshCommunities(current, token);
    }

    private void refreshUsers(ClientSession current, long token) throws IOException {
        long revision = connectionRevision.get();
        List<String> snapshot = current.users();
        JavaFxUtils.runOnFxThread(() -> {
            if (generation.get() != token || connectionRevision.get() != revision || !current.isServerConnected() || snapshot.equals(onlineUsers)) return;
            onlineUsers = snapshot;
            listeners.forEach(l -> l.onUserListUpdated(snapshot));
        });
    }

    private void refreshCommunities(ClientSession current, long token) throws IOException {
        long revision = connectionRevision.get();
        List<CommunityInfo> data = current.communities().list();
        JavaFxUtils.runOnFxThread(() -> {
            if (generation.get() != token || connectionRevision.get() != revision || !current.isServerConnected()) return;
            List<CommunityModel> snapshot = new ArrayList<>();
            for (CommunityInfo entry : data) snapshot.add(new CommunityModel(entry.id(), entry.name(),
                    entry.owner(), entry.memberCount(), entry.member()));
            Map<String, CommunityModel> existing = new java.util.HashMap<>();
            communities.forEach(model -> existing.put(model.getId(), model));
            List<CommunityModel> updated = new ArrayList<>();
            for (CommunityModel latest : snapshot) {
                CommunityModel model = existing.get(latest.getId());
                if (model == null) model = latest;
                else model.updateFrom(latest);
                updated.add(model);
            }
            communities = List.copyOf(updated);
            listeners.forEach(l -> l.onCommunityListUpdated(communities));
        });
    }

    private void selected(CommunityClient.Selection selection) {
        if (generation.get() != commandToken) return;
        dispatchServer(l -> l.onCommunityJoined(selection.id(), selection.name()));
        requestCommunityList();
    }

    public void createCommunity(String name) { submitServer(s -> selected(s.communities().create(name.trim()))); }
    public void joinCommunity(String id) { submitServer(s -> selected(s.communities().join(id))); }
    public void selectCommunity(String id) { submitServer(s -> selected(s.communities().select(id))); }
    public void leaveCommunity(String id) {
        submitServer(s -> {
            s.communities().leave(id);
            if (generation.get() != commandToken) return;
            dispatchServer(l -> l.onCommunityLeft(id));
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
    public void sendChatMessage(String peer, String text, Consumer<String> result) {
        submitMessage(s -> s.sendMessage(peer, text), result);
    }
    public void broadcastMessage(String id, String text, Consumer<String> result) {
        long revision = connectionRevision.get();
        submitMessage(s -> {
            if (!isServerConnected() || revision != connectionRevision.get()) throw new IOException("Server unavailable");
            s.broadcastMessage(id, text);
        }, result);
    }
    private void submitMessage(SessionTask task, Consumer<String> result) {
        long token = generation.get();
        ClientSession current = session;
        executor.execute(() -> {
            String failure = null;
            try {
                if (current == null || current != session || generation.get() != token) throw new IOException("Session closed");
                task.run(current);
            } catch (IOException | RuntimeException e) {
                failure = java.util.Objects.requireNonNullElse(e.getMessage(), "Send failed");
            }
            String error = failure;
            JavaFxUtils.runOnFxThread(() -> { if (generation.get() == token) result.accept(error); });
        });
    }
    public void sendFile(String peer, File file) { submit(s -> { s.ensurePeer(peer); s.sendFile(peer, file); }); }
    public void broadcastMessage(String communityId, String text) { submitServer(s -> s.broadcastMessage(communityId, text)); }
    public void broadcastFile(String communityId, String communityName, File file) {
        CommunityClient.Selection target = new CommunityClient.Selection(communityId, communityName);
        submitServer(s -> s.broadcastFile(target, file));
    }
    public void acceptFileOffer(String id) { decideFile(id, true); }
    public void rejectFileOffer(String id) { decideFile(id, false); }
    private void decideFile(String id, boolean accept) {
        submit(s -> {
            try {
                if (accept) s.accept(id); else s.reject(id);
            } catch (IOException | RuntimeException e) {
                events(commandToken).onFileState(id, ClientEvents.FileState.FAILED, e.getMessage());
            }
        });
    }

    private ClientEvents events(long token) {
        return new ClientEvents() {
            public void onSystemMessage(String content) { dispatch(token, l -> l.onSystemNotification(content, false)); }
            public void onMessage(String peer, String content) { dispatch(token, l -> l.onMessageReceived(peer, content)); }
            public void onBroadcast(String id, String sender, String content) {
                dispatch(token, l -> l.onBroadcastMessageReceived(id, sender, content));
            }
            public void onPeerConnected(String peer) { dispatch(token, l -> l.onPeerConnected(peer)); }
            public void onPeerDisconnected(String peer) { dispatch(token, l -> l.onPeerDisconnected(peer)); }
            public void onServerClosed() {
                synchronized (ChatClientAdapter.this) {
                    if (generation.get() != token) return;
                    onlineUsers = List.of();
                    connectionRevision.incrementAndGet();
                    if (poller != null) { poller.cancel(false); poller = null; }
                }
                dispatch(token, listener -> {
                    listener.onUserListUpdated(List.of());
                    listener.onConnectionClosed();
                });
            }
            public void onFileOffer(FileOffer offer) {
                String id = offer.id();
                FileTransferMeta meta = offer.meta();
                String communityId = offer.communityId();
                boolean incoming = offer.incoming();
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
            public void onFileState(String id, FileState state, String detail) {
                JavaFxUtils.runOnFxThread(() -> {
                    if (generation.get() != token) return;
                    FileOfferModel model = fileOffers.get(id);
                    if (model == null) {
                        if (state == FileState.FAILED) listeners.forEach(l -> l.onSystemNotification(detail, true));
                        return;
                    }
                    if (model.getStatus() == FileOfferModel.Status.COMPLETED
                            || model.getStatus() == FileOfferModel.Status.FAILED
                            || model.getStatus() == FileOfferModel.Status.REJECTED) return;
                    model.setStatus(switch (state) {
                        case TRANSFERRING -> FileOfferModel.Status.TRANSFERRING;
                        case REJECTED -> FileOfferModel.Status.REJECTED;
                        case COMPLETED -> FileOfferModel.Status.COMPLETED;
                        case FAILED -> FileOfferModel.Status.FAILED;
                    });
                    if (state == FileState.COMPLETED) {
                        model.setLocalPath(detail);
                        model.setProgress(1);
                    } else if (detail != null) model.setStatusText(detail);
                });
            }
        };
    }

    public synchronized void logout() {
        generation.incrementAndGet();
        loggingIn = false;
        refreshing = false;
        connectionRevision.incrementAndGet();
        if (poller != null) { poller.cancel(false); poller = null; }
        ClientSession closing = session;
        session = null;
        onlineUsers = List.of();
        communities = List.of();
        fileOffers.clear();
        if (closing != null) Thread.startVirtualThread(closing::close);
    }
}
