package client;

import com.google.gson.JsonArray;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import logic.PeerLookupResult;
import protocols.Json;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketChannel;
import protocols.PacketValidator;

/** State and resources of one login; a subsequent login creates a new session. */
public final class ClientSession implements AutoCloseable {
    private final ClientEvents events;
    private final String username;
    private final ServerConnection server;
    private final PeerListener listener;
    private final CommunityClient communities;
    private final BroadcastFileController broadcasts;
    private final FileTransferController files;
    private final ConcurrentHashMap<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private final Object peerLock = new Object();
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile String selectedPeer;
    private volatile boolean started;

    ClientSession(String username, String host, int port, PacketChannel channel) throws IOException {
        this(username, host, port, channel, ClientEvents.NONE);
    }

    ClientSession(String username, String host, int port, PacketChannel channel, ClientEvents events) throws IOException {
        this.events = java.util.Objects.requireNonNull(events);
        this.username = username;
        server = new ServerConnection(channel, this::receiveEvent, () -> {
            if (started && !closing.get()) events.onServerClosed();
        });
        listener = new PeerListener(this::registerPeer, this::receiveFile);
        AtomicLong offerNumbers = new AtomicLong();
        files = new FileTransferController(username, listener.getPort(), new File("downloads/" + username),
                peers, offerNumbers::incrementAndGet, events);
        broadcasts = new BroadcastFileController(server, host, port, username, offerNumbers::incrementAndGet, events);
        communities = new CommunityClient(server);
    }

    String username() { return username; }
    int peerPort() { return listener.getPort(); }
    public CommunityClient communities() { return communities; }

    public void start() {
        started = true;
        server.start();
        Thread.startVirtualThread(listener);
    }

    public JsonArray users() throws IOException {
        return Json.array(request(Packet.empty(OpCode.USER_LIST_REQUEST), OpCode.USER_LIST_RESPONSE).jsonBody(), "users");
    }

    JsonArray chats() throws IOException {
        return Json.array(request(Packet.empty(OpCode.CHAT_SESSION_LIST_REQUEST), OpCode.CHAT_SESSION_LIST_RESPONSE)
                .jsonBody(), "sessions");
    }

    PeerLookupResult lookup(String target) throws IOException {
        Packet response = server.request(Packet.json(OpCode.PEER_LOOKUP_REQUEST,
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

    String selectedPeer() { return selectedPeer; }

    List<String> connectedPeers() {
        synchronized (peerLock) {
            return peers.entrySet().stream()
                    .filter(entry -> !entry.getValue().isClosed())
                    .map(Map.Entry::getKey).sorted().toList();
        }
    }

    boolean selectPeer(String target) {
        synchronized (peerLock) {
            PeerConnection peer = peers.get(target);
            if (closing.get() || peer == null || peer.isClosed()) {
                selectedPeer = null;
                return false;
            }
            selectedPeer = target;
            return true;
        }
    }

    void clearPeerSelection() {
        synchronized (peerLock) { selectedPeer = null; }
    }

    void connectPeer(String target, InetSocketAddress address) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(address, 10000);
            PeerConnection connection = new PeerConnection(new PacketChannel(socket));
            connection.initiateHandshake(username, peerPort());
            if (registerPeer(target, connection) && selectPeer(target)) {
                System.out.println("Now chatting with " + target);
            }
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    boolean sendMessage(String message) throws IOException {
        PeerConnection peer;
        synchronized (peerLock) {
            peer = selectedPeer == null ? null : peers.get(selectedPeer);
            if (peer == null || peer.isClosed()) {
                selectedPeer = null;
                return false;
            }
        }
        try {
            peer.sendMessage(message);
            return true;
        } catch (IOException e) {
            closePeer(peer);
            throw e;
        }
    }

    public void sendFile(String target, File file) {
        files.offer(peers.get(target), target, file);
    }

    public void accept(String number) throws IOException {
        if (!broadcasts.accept(number)) files.sendDecision(number, "ACCEPT");
    }

    public void reject(String number) throws IOException {
        if (!broadcasts.reject(number)) files.sendDecision(number, "REJECT");
    }

    public void broadcastMessage(String message) throws IOException {
        if (message.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new IOException("Message exceeds limit: 65536");
        }
        CommunityClient.Selection community = communities.requireSelection();
        server.send(Packet.json(OpCode.CHAT_MESSAGE_BROADCAST,
                Json.object("communityId", community.id(), "content", message)));
    }

    public void broadcastFile(File file) throws IOException {
        broadcasts.upload(file, communities.requireSelection());
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
        Packet response = server.request(packet, responseOp);
        PacketValidator.requireSuccess(response);
        return response;
    }

    private void receiveEvent(Packet packet) throws IOException {
        switch (packet.op()) {
            case CHAT_MESSAGE -> System.out.println(packet.string("content"));
            case CHAT_MESSAGE_BROADCAST -> {
                events.onBroadcast(packet.string("communityId"), packet.string("sender"), packet.string("content"));
                System.out.println("(" + packet.string("communityId") + ":broadcast) "
                        + packet.string("sender") + " : " + packet.string("content"));
            }
            case FILE_BROADCAST_OFFER -> broadcasts.onOffer(packet);
            default -> throw new IOException("Unexpected server event: " + packet.op());
        }
    }

    private void receiveFile(PacketChannel channel, Packet hello) {
        files.receiveFileConnection(channel, hello);
    }

    private boolean registerPeer(String name, PeerConnection connection) {
        PeerConnection previous = null;
        // Publishing and the CONNECTED report are ordered against DISCONNECTED on
        // this peer only. The registry lock is never held while waiting for I/O.
        synchronized (connection) {
            boolean rejected;
            synchronized (peerLock) {
                rejected = closing.get();
                if (!rejected) {
                    boolean firstPeer = peers.isEmpty();
                    previous = peers.put(name, connection);
                    if (selectedPeer == null && firstPeer) selectedPeer = name;
                }
            }
            if (rejected) {
                closePeer(connection);
                return false;
            }
            reportChatSession(name, connection, "CONNECTED");
        }
        if (previous != null) {
            closePeer(previous);
            reportChatSession(name, previous, "DISCONNECTED");
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
            if (!closing.get()) System.err.println("Peer " + name + ": " + e.getMessage());
        } finally {
            files.closeForPeer(connection);
            reportChatSession(name, connection, "DISCONNECTED");
            synchronized (peerLock) {
                if (peers.remove(name, connection)) {
                    if (name.equals(selectedPeer)) selectedPeer = null;
                    events.onPeerDisconnected(name);
                }
            }
            closePeer(connection);
        }
    }

    private void reportChatSession(String name, PeerConnection connection, String state) {
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
        if (!closing.compareAndSet(false, true)) return;
        try { listener.close(); } catch (IOException e) {
            System.err.println("Peer listener error: " + e.getMessage());
        }
        broadcasts.close();
        files.close();
        Map<String, PeerConnection> closingPeers;
        synchronized (peerLock) {
            closingPeers = Map.copyOf(peers);
            peers.clear();
            selectedPeer = null;
        }
        for (var peer : closingPeers.entrySet()) {
            closePeer(peer.getValue());
            reportChatSession(peer.getKey(), peer.getValue(), "DISCONNECTED");
        }
        try {
            if (started && server.isOpen()) {
                PacketValidator.requireSuccess(server.request(Packet.empty(OpCode.LOGOUT_REQUEST), OpCode.LOGOUT_RESPONSE));
            }
        } catch (IOException e) {
            System.err.println("Logout: " + e.getMessage());
        } finally {
            try { server.close(); } catch (IOException e) {
                System.err.println("Cannot close server connection: " + e.getMessage());
            }
            broadcasts.awaitWorkers();
        }
    }
}
