package server.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChatSessionRegistry {
    private final ConcurrentHashMap<UUID, ChatSession> sessions = new ConcurrentHashMap<>();

    public synchronized boolean connected(UUID id, UserSession reporter, UserSession peer) {
        if (reporter.username().equals(peer.username()))
            return false;
        ChatSession current = sessions.get(id);
        if (current == null) {
            sessions.put(id, new ChatSession(id, reporter, peer, ChatSession.State.CONNECTED));
            return true;
        }
        if (current.state() == ChatSession.State.UNKNOWN
                && ((current.userA().username().equals(reporter.username()) && current.userB().username().equals(peer.username()))
                || (current.userB().username().equals(reporter.username()) && current.userA().username().equals(peer.username())))) {
            sessions.put(id, new ChatSession(id, reporter, peer, ChatSession.State.CONNECTED));
            return true;
        }
        return matches(current, reporter, peer);
    }

    public synchronized boolean disconnected(UUID id, UserSession reporter, String peerUsername) {
        ChatSession current = sessions.get(id);
        if (current == null || !current.hasParticipant(reporter)
                || !current.otherUser(reporter).username().equals(peerUsername)) {
            return false;
        }
        sessions.put(id, current.withState(ChatSession.State.DISCONNECTED));
        return true;
    }

    public synchronized void userDisconnected(UserSession user) {
        sessions.replaceAll((id, session) -> session.hasParticipant(user)
                && session.state() == ChatSession.State.CONNECTED
                        ? session.withState(ChatSession.State.UNKNOWN)
                        : session);
    }

    public Optional<ChatSession> find(UUID id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public List<ChatSession> listSessions() {
        return List.copyOf(sessions.values());
    }

    private boolean matches(ChatSession session, UserSession reporter, UserSession peer) {
        return session.hasParticipant(reporter) && session.otherUser(reporter) == peer;
    }

    public synchronized boolean isConnected(String usernameA, String usernameB) {
        return sessions.values().stream().anyMatch(session -> session.state() == ChatSession.State.CONNECTED
                && ((session.userA().username().equals(usernameA)
                        && session.userB().username().equals(usernameB))
                        ||
                        (session.userA().username().equals(usernameB)
                                && session.userB().username().equals(usernameA))));
    }

}
