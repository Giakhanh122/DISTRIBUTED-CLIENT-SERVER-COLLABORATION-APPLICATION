package server.session;

import java.util.UUID;

public record ChatSession(
    UUID sessionId,
    UserSession userA,
    UserSession userB,
    State state
) {
    public enum State { CONNECTED, DISCONNECTED, UNKNOWN }

    public boolean hasParticipant(UserSession user) {
        return userA == user || userB == user;
    }

    UserSession otherUser(UserSession user) {
        return userA == user ? userB : userA;
    }

    ChatSession withState(State next) {
        return state == next ? this : new ChatSession(sessionId, userA, userB, next);
    }
}
