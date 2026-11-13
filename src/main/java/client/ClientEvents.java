package client;

/** Structured session events shared by console and GUI without depending on JavaFX. */
public interface ClientEvents {
    enum FileState { TRANSFERRING, REJECTED, COMPLETED, FAILED }

    ClientEvents NONE = new ClientEvents() {};
    default void onSystemMessage(String content) {}
    default void onMessage(String peer, String content) {}
    default void onBroadcast(String communityId, String sender, String content) {}
    default void onPeerConnected(String peer) {}
    default void onPeerDisconnected(String peer) {}
    default void onServerClosed() {}
    default void onServerReconnected() {}
    default void onFileOffer(FileOffer offer) {}
    default void onFileState(String id, FileState state, String detail) {}
}
