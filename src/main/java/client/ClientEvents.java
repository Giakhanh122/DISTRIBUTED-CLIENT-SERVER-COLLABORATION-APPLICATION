package client;

import client.transfer.FileOffer;

/** Structured session events shared by console and GUI without depending on JavaFX. */
public interface ClientEvents {
    enum FileState { TRANSFERRING, REJECTED, COMPLETED, FAILED }

    ClientEvents NONE = new ClientEvents() {};
    default void onSystemMessage(String content) {}
    default void onMessage(String peer, String content) {}
    default void onMessageDelivered(String peer, String messageId) {}
    default void onTyping(String peer, boolean typing) {}
    default void onBroadcast(String communityId, String sender, String content) {}
    default void onGlobalBroadcast(String sender, String content) {}
    default void onPresenceUpdate(String username, boolean online) {}
    default void onProtocolError(String code, String reason, boolean fatal) {}
    default void onPeerConnected(String peer) {}
    default void onPeerDisconnected(String peer) {}
    default void onServerClosed() {}
    default void onServerReconnected() {}
    default void onFileOffer(FileOffer offer) {}
    default void onFileState(String id, FileState state, String detail) {}
}
