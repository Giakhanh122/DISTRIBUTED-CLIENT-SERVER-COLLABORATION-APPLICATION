package client;

import client.community.CommunityClient;
import client.connection.ServerConnection;
import client.peer.PeerConnection;
import client.peer.PeerListener;
import client.peer.PeerLookupResult;
import client.security.ServerKeyPinning;
import client.transfer.BroadcastFileController;
import client.transfer.FileTransferController;

import com.google.gson.JsonArray;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import protocols.Json;
import protocols.OpCode;
import protocols.Packet;
import network.PacketChannel;
import protocols.PacketValidator;
import security.PeerKeyProof;

/** State and resources of one login; a subsequent login creates a new session. */
public final class ClientSession implements AutoCloseable {
    private final ClientEvents events;
    private final ServerKeyPinning serverKeyPinning;
    private final String username;
    private final String host;
    private final int port;
    private final AtomicLong offerNumbers = new AtomicLong();
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.CONNECTING);
    private volatile ServerContext serverContext;
    private final PeerListener listener;
    private final CommunityClient communities;
    private final FileTransferController files;
    private final ConcurrentHashMap<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private final Set<Socket> connectingSockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService reports = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("peer-reports-", 0).factory());
    private final Object peerLock = new Object();
    private volatile String selectedPeer;

    /** One control-connection generation; old readers can never dispatch into a replacement. */
    private final class ServerContext {
        final ServerConnection server;
        final BroadcastFileController broadcasts;

        ServerContext(PacketChannel channel) {
            server = new ServerConnection(channel, packet -> {
                if (serverContext == this) receiveEvent(this, packet);
            }, () -> serverClosed(this));
            broadcasts = new BroadcastFileController(server, host, port, username, offerNumbers::incrementAndGet, events);
        }

    }

    private void serverClosed(ServerContext source) {
        boolean notify;
        synchronized (peerLock) {
            ConnectionState current = state.get();
            notify = serverContext == source && current != ConnectionState.CONNECTING && current != ConnectionState.CLOSED;
            if (notify) state.set(ConnectionState.SERVER_OFFLINE);
        }
        source.broadcasts.close();
        if (notify) events.onServerClosed();
    }

    ClientSession(String username, String host, int port, PacketChannel channel, ClientEvents events,
                  ServerKeyPinning serverKeyPinning) throws IOException {
        this.events = java.util.Objects.requireNonNull(events);
        this.serverKeyPinning = java.util.Objects.requireNonNull(serverKeyPinning);
        this.username = username;
        this.host = host;
        this.port = port;
        listener = new PeerListener(serverKeyPinning, this::requestPeerKey, this::registerPeer, this::receiveFile);
        files = new FileTransferController(username, new File("downloads/" + username),
                peers, offerNumbers::incrementAndGet, events);
        serverContext = new ServerContext(channel);
        communities = new CommunityClient(() -> serverContext.server);
    }

    public String username() { return username; }
    int peerPort() { return listener.getPort(); }
    public ConnectionState connectionState() { return state.get(); }
    private boolean isClosed() { return state.get() == ConnectionState.CLOSED; }
    public boolean isServerConnected() { return state.get() == ConnectionState.ONLINE && serverContext.server.isOpen(); }
    public boolean isPeerConnected(String target) {
        PeerConnection peer = peers.get(target);
        return !isClosed() && peer != null && !peer.isClosed();
    }
    public CommunityClient communities() { return communities; }

    public void start() {
        synchronized (peerLock) {
            if (!state.compareAndSet(ConnectionState.CONNECTING, ConnectionState.ONLINE)) {
                throw new IllegalStateException("Session already started or closed");
            }
            try {
                serverContext.server.start();
                Thread.startVirtualThread(listener);
            } catch (RuntimeException e) {
                close();
                throw e;
            }
        }
    }

    public void disconnectServer() throws IOException { serverContext.server.close(); }

    /** Manual reconnect: no password retention and no replay of old requests or messages. */
    public void reconnect(String password) throws IOException {
        if (password == null || password.isEmpty()) throw new IOException("Password cannot be empty");
        if (!state.compareAndSet(ConnectionState.SERVER_OFFLINE, ConnectionState.RECONNECTING)) {
            throw new IOException("Reconnect requires a disconnected server session");
        }
        Socket socket = new Socket();
        ServerContext previous = serverContext;
        boolean installed = false;
        try {
            synchronized (peerLock) {
                if (isClosed()) throw new IOException("Session is closed");
                if (previous.server.isOpen()) throw new IOException("Server is still connected");
                connectingSockets.add(socket);
            }
            PacketChannel channel = new Client().reconnect(socket, host, port, username, password, peerPort());
            synchronized (peerLock) {
                if (isClosed() || serverContext != previous) throw new IOException("Session changed during reconnect");
                ServerContext replacement = new ServerContext(channel);
                serverContext = replacement;
                communities.clearSelection();
                replacement.server.start();
                installed = true;
                peers.forEach((name, peer) -> {
                    if (!peer.isClosed()) queueChatReport(name, peer, "CONNECTED");
                });
            }
            if (!state.compareAndSet(ConnectionState.RECONNECTING, ConnectionState.ONLINE) || !isServerConnected()) throw new IOException("Server disconnected during reconnect");
            events.onServerReconnected();
        } finally {
            connectingSockets.remove(socket);
            state.compareAndSet(ConnectionState.RECONNECTING, ConnectionState.SERVER_OFFLINE);
            if (!installed) socket.close();
        }
    }

    public List<String> users() throws IOException {
        JsonArray entries = Json.array(request(Packet.empty(OpCode.USER_LIST_REQUEST), OpCode.USER_LIST_RESPONSE).jsonBody(), "users");
        java.util.ArrayList<String> users = new java.util.ArrayList<>();
        for (var entry : entries) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) throw new IOException("Invalid username in user list");
            users.add(entry.getAsString());
        }
        return List.copyOf(users);
    }

    public JsonArray chats() throws IOException {
        return Json.array(request(Packet.empty(OpCode.CHAT_SESSION_LIST_REQUEST), OpCode.CHAT_SESSION_LIST_RESPONSE)
                .jsonBody(), "sessions");
    }

    public PeerKeyProof requestPeerKey(UUID sessionId, PublicKey publicKey) throws IOException {
        ServerContext source = serverContext;
        if (!isServerConnected()) throw new IOException("Server connection required to certify a peer public key");
        String id = sessionId.toString();
        String encodedPublicKey = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        Packet response = source.server.request(Packet.json(OpCode.PEER_KEY_REQUEST,
                Json.object("sessionId", id, "publicKey", encodedPublicKey)), OpCode.PEER_KEY_RESPONSE);
        PacketValidator.requireSuccess(response);
        if (source != serverContext) throw new IOException("Server connection changed while certifying peer public key");

        PeerKeyProof proof = new PeerKeyProof(response.string("username"), response.string("sessionId"),
                response.string("publicKey"), response.string("serverSignature"));
        if (!proof.username().equals(username) || !proof.sessionId().equals(id)
                || !proof.publicKey().equals(encodedPublicKey)) {
            throw new IOException("Peer public key confirmation does not match request");
        }
        try {
            serverKeyPinning.verifyPeerKey(proof, username, sessionId);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot verify server confirmation of peer public key", e);
        }
        return proof;
    }

    PeerLookupResult lookup(String target) throws IOException {
        Packet response = serverContext.server.request(Packet.json(OpCode.PEER_LOOKUP_REQUEST,
                Json.object("username", target)), OpCode.PEER_LOOKUP_RESPONSE);
        return switch (response.status()) {
            case "USER_OFFLINE" -> new PeerLookupResult(PeerLookupResult.Status.USER_OFFLINE, null);
            case "ALREADY_CONNECTED" -> new PeerLookupResult(PeerLookupResult.Status.ALREADY_CONNECTED, null);
            case "OK" -> {
                String ip = response.string("ip");
                int port = response.integer("peerPort");
                if (ip.isBlank() || port < 1 || port > 65535) throw new IOException("Invalid peer address payload");
                yield new PeerLookupResult(PeerLookupResult.Status.SUCCESS, new InetSocketAddress(ip, port));
            }
            default -> throw new IOException("Lookup failed: " + response.status());
        };
    }

    public String selectedPeer() { return selectedPeer; }

    public List<String> connectedPeers() {
        synchronized (peerLock) {
            return peers.entrySet().stream()
                    .filter(entry -> !entry.getValue().isClosed())
                    .map(Map.Entry::getKey).sorted().toList();
        }
    }

    public boolean selectPeer(String target) {
        synchronized (peerLock) {
            PeerConnection peer = peers.get(target);
            if (isClosed() || peer == null || peer.isClosed()) {
                selectedPeer = null;
                return false;
            }
            selectedPeer = target;
            return true;
        }
    }

    void connectPeer(String target, InetSocketAddress address) throws IOException {
        Socket socket = new Socket();
        try {
            synchronized (peerLock) {
                if (isClosed()) throw new IOException("Session closed");
                connectingSockets.add(socket);
            }
            socket.connect(address, 10000);
            PeerConnection connection = new PeerConnection(new PacketChannel(socket));
            PeerKeyProof proof = requestPeerKey(connection.sessionId(), connection.publicKey());
            connection.initiateHandshake(peerPort(), proof, target, serverKeyPinning);
            if (registerPeer(target, connection)) selectPeer(target);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        } finally { connectingSockets.remove(socket); }
    }

    public boolean sendMessage(String message) throws IOException {
        PeerConnection peer;
        synchronized (peerLock) {
            peer = selectedPeer == null ? null : peers.get(selectedPeer);
            if (peer == null || peer.isClosed()) {
                selectedPeer = null;
                return false;
            }
        }
        // PacketChannel closes on I/O failure; local validation must leave a healthy peer open.
        peer.sendMessage(message);
        return true;
    }

    public void sendFile(String target, File file) {
        files.offer(peers.get(target), target, file);
    }

    public void accept(String number) throws IOException {
        if (!serverContext.broadcasts.accept(number)) files.sendDecision(number, "ACCEPT");
    }

    public void reject(String number) throws IOException {
        if (!serverContext.broadcasts.reject(number)) files.sendDecision(number, "REJECT");
    }

    public void broadcastMessage(String message) throws IOException {
        broadcastMessage(communities.requireSelection().id(), message);
    }

    public void broadcastMessage(String communityId, String message) throws IOException {
        if (message.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new IOException("Message exceeds limit: 65536");
        }
        serverContext.server.send(Packet.json(OpCode.CHAT_MESSAGE_BROADCAST,
                Json.object("communityId", communityId, "content", message)));
    }

    public void broadcastFile(File file) throws IOException {
        broadcastFile(communities.requireSelection(), file);
    }

    public void broadcastFile(CommunityClient.Selection community, File file) throws IOException {
        serverContext.broadcasts.upload(file, community);
    }

    public void ensurePeer(String target) throws IOException {
        if (target == null || target.isBlank() || target.equals(username)) throw new IOException("Invalid peer username");
        if (selectPeer(target)) return;
        PeerLookupResult result = lookup(target);
        if (result.status() == PeerLookupResult.Status.USER_OFFLINE) throw new IOException(target + " is offline");
        if (result.status() == PeerLookupResult.Status.SUCCESS) connectPeer(target, result.address());
        if (!selectPeer(target)) throw new IOException("Cannot connect to " + target);
    }

    public void sendMessage(String target, String message) throws IOException {
        ensurePeer(target);
        PeerConnection peer = peers.get(target);
        if (peer == null) throw new IOException("Peer disconnected: " + target);
        peer.sendMessage(message);
    }

    private Packet request(Packet packet, OpCode responseOp) throws IOException {
        Packet response = serverContext.server.request(packet, responseOp);
        PacketValidator.requireSuccess(response);
        return response;
    }

    private void receiveEvent(ServerContext source, Packet packet) throws IOException {
        switch (packet.op()) {
            case CHAT_MESSAGE -> events.onSystemMessage(packet.string("content"));
            case CHAT_MESSAGE_BROADCAST -> {
                events.onBroadcast(packet.string("communityId"), packet.string("sender"), packet.string("content"));
            }
            case FILE_BROADCAST_OFFER -> source.broadcasts.onOffer(packet);
            default -> throw new IOException("Unexpected server event: " + packet.op());
        }
    }

    private void receiveFile(PacketChannel channel, Packet hello) {
        files.receiveFileConnection(channel, hello);
    }

    private boolean registerPeer(String name, PeerConnection connection) {
        PeerConnection previous = null;
        // Enqueue CONNECTED while publishing the peer, before its reader can enqueue
        // DISCONNECTED. The report worker performs network I/O outside peerLock.
        synchronized (connection) {
            boolean rejected;
            synchronized (peerLock) {
                rejected = isClosed();
                if (!rejected) {
                    PeerConnection existing = peers.get(name);
                    // Both ends choose the same UUID when outgoing handshakes cross.
                    rejected = existing != null && !existing.isClosed()
                            && existing.sessionId().compareTo(connection.sessionId()) <= 0;
                    if (!rejected) {
                        boolean firstPeer = peers.isEmpty();
                        previous = peers.put(name, connection);
                        queueChatReport(name, connection, "CONNECTED");
                        if (selectedPeer == null && firstPeer) selectedPeer = name;
                    }
                }
            }
            if (rejected) {
                closePeer(connection);
                return false;
            }
        }
        if (previous != null) {
            closePeer(previous);
            queueChatReport(name, previous, "DISCONNECTED");
        }
        events.onPeerConnected(name);
        Thread.startVirtualThread(() -> readPeer(name, connection));
        return true;
    }

    private void readPeer(String name, PeerConnection connection) {
        try {
            connection.readMessages(name, content -> events.onMessage(name, content), packet -> {
                try {
                    if (packet.op() == OpCode.FILE_OFFER) files.handleIncomingOffer(name, connection, packet);
                    else files.handleIncomingDecision(connection, packet);
                } catch (IOException | RuntimeException e) {
                    System.err.println("Invalid peer file packet: " + e.getMessage());
                }
            });
        } catch (IOException e) {
            if (!isClosed()) System.err.println("Peer " + name + ": " + e.getMessage());
        } finally {
            closePeer(connection);
            files.closeForPeer(connection);
            queueChatReport(name, connection, "DISCONNECTED");
            synchronized (peerLock) {
                if (peers.remove(name, connection)) {
                    if (name.equals(selectedPeer)) selectedPeer = null;
                    events.onPeerDisconnected(name);
                }
            }
        }
    }

    private void queueChatReport(String name, PeerConnection connection, String state) {
        synchronized (peerLock) {
            ServerContext source = serverContext;
            if (!isClosed()) reports.execute(() -> {
                if (serverContext == source) reportChatSession(source.server, name, connection, state);
            });
        }
    }

    private void reportChatSession(ServerConnection server, String name, PeerConnection connection, String state) {
        synchronized (connection) {
            if (!server.isOpen()) return;
            if (state.equals("DISCONNECTED") && !connection.markDisconnectReported()) return;
            try {
                Packet response = server.request(Packet.json(OpCode.CHAT_SESSION_REPORT,
                        Json.object("sessionId", connection.sessionId().toString(),
                                "peerUsername", name, "state", state)), OpCode.CHAT_SESSION_REPORT_RESPONSE);
                if (!response.status().equals("OK")) {
                    System.err.println("Chat session report rejected: " + response.status());
                }
            } catch (IOException e) {
                System.err.println("Cannot report chat session: " + e.getMessage());
            }
        }
    }

    private void closePeer(PeerConnection connection) {
        try { connection.close(); } catch (IOException e) {
            System.err.println("Cannot close peer: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        ServerContext current;
        Map<String, PeerConnection> closingPeers;
        Set<Socket> pendingSockets;
        ConnectionState previousState;
        synchronized (peerLock) {
            previousState = state.getAndSet(ConnectionState.CLOSED);
            if (previousState == ConnectionState.CLOSED) return;
            current = serverContext;
            closingPeers = Map.copyOf(peers);
            pendingSockets = Set.copyOf(connectingSockets);
            peers.clear();
            selectedPeer = null;
        }
        try { listener.close(); } catch (IOException e) {
            System.err.println("Peer listener error: " + e.getMessage());
        }
        for (Socket socket : pendingSockets) {
            try { socket.close(); } catch (IOException e) {
                System.err.println("Cannot close pending peer: " + e.getMessage());
            }
        }
        current.broadcasts.close();
        files.close();
        closingPeers.values().forEach(this::closePeer);
        // Keep intentional logout metadata when the server responds, but never let
        // management I/O prevent closing local sockets. The FIFO worker preserves report order.
        try {
            if (previousState != ConnectionState.CONNECTING && current.server.isOpen()) {
                var graceful = reports.submit(() -> {
                    for (var peer : closingPeers.entrySet()) reportChatSession(current.server, peer.getKey(), peer.getValue(), "DISCONNECTED");
                    try {
                        if (current.server.isOpen()) PacketValidator.requireSuccess(current.server.request(
                                Packet.empty(OpCode.LOGOUT_REQUEST), OpCode.LOGOUT_RESPONSE));
                    } catch (IOException e) {
                        System.err.println("Logout: " + e.getMessage());
                    }
                });
                graceful.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            System.err.println("Graceful logout incomplete: " + e.getClass().getSimpleName());
        } finally {
            try { current.server.close(); } catch (IOException e) {
                System.err.println("Cannot close server connection: " + e.getMessage());
            }
            reports.shutdownNow();
        }
        current.broadcasts.awaitWorkers();
    }
}
