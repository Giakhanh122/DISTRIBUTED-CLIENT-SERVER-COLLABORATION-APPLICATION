package server.session;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class UserSessionRegistry {
    private final ConcurrentHashMap<String, UserSession> onlineUsers =
        new ConcurrentHashMap<>();

    public boolean register(UserSession session) {
        return onlineUsers.putIfAbsent(session.username(), session) == null;
    }

    public Optional<UserSession> find(String username) {
        return Optional.ofNullable(onlineUsers.get(username));
    }

    public boolean remove(UserSession session) {
        return onlineUsers.remove(session.username(), session);
    }

    public List<UserSession> listOnlineUsers() {
        return List.copyOf(onlineUsers.values());
    }
}
