package client;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry managing active peer connections and the current active peer context.
 */
public class PeerConnectionRegistry {

    private final ConcurrentHashMap<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private volatile String activePeerUsername;
    private volatile boolean closing;

    public synchronized boolean register(String username, PeerConnection connection) {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(connection, "connection must not be null");

        if (closing) {
            return false;
        }

        PeerConnection existing = peers.get(username);
        if (existing != null && !existing.isClosed()) {
            return false;
        }

        peers.put(username, connection);
        if (activePeerUsername == null) {
            activePeerUsername = username;
        }
        return true;
    }

    public synchronized boolean unregister(String username, PeerConnection connection) {
        if (username == null || connection == null) {
            return false;
        }
        boolean removed = peers.remove(username, connection);
        if (removed && username.equals(activePeerUsername)) {
            activePeerUsername = null;
        }
        return removed;
    }

    public PeerConnection get(String username) {
        if (username == null) {
            return null;
        }
        PeerConnection connection = peers.get(username);
        if (connection != null && connection.isClosed()) {
            peers.remove(username, connection);
            synchronized (this) {
                if (username.equals(activePeerUsername)) {
                    activePeerUsername = null;
                }
            }
            return null;
        }
        return connection;
    }

    public boolean isConnected(String username) {
        PeerConnection conn = get(username);
        return conn != null && !conn.isClosed();
    }

    public String getActivePeer() {
        String active = activePeerUsername;
        if (active != null && !isConnected(active)) {
            synchronized (this) {
                if (active.equals(activePeerUsername) && !isConnected(active)) {
                    activePeerUsername = null;
                }
            }
            return null;
        }
        return activePeerUsername;
    }

    public synchronized boolean setActivePeer(String username) {
        if (username == null) {
            activePeerUsername = null;
            return true;
        }
        if (isConnected(username)) {
            activePeerUsername = username;
            return true;
        }
        return false;
    }

    public synchronized void clearActivePeer() {
        activePeerUsername = null;
    }

    public ConcurrentHashMap<String, PeerConnection> asMap() {
        return peers;
    }

    public List<String> getConnectedPeers() {
        return peers.entrySet().stream()
                .filter(e -> !e.getValue().isClosed())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    public int size() {
        return (int) peers.values().stream().filter(c -> !c.isClosed()).count();
    }

    public synchronized void setClosing(boolean closing) {
        this.closing = closing;
    }

    public boolean isClosing() {
        return closing;
    }

    public synchronized void clear() {
        peers.clear();
        activePeerUsername = null;
    }
}

