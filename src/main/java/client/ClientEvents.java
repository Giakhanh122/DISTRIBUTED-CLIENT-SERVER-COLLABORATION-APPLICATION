package client;

import protocols.FileTransferMeta;

/** Session events shared by graphical clients without depending on JavaFX. */
public interface ClientEvents {
    enum FileState { TRANSFERRING, REJECTED, COMPLETED, FAILED }

    ClientEvents NONE = new ClientEvents() {};
    default void onMessage(String peer, String content) {}
    default void onBroadcast(String communityId, String sender, String content) {}
    default void onPeerConnected(String peer) {}
    default void onPeerDisconnected(String peer) {}
    default void onServerClosed() {}
    default void onFileOffer(String id, FileTransferMeta meta, String communityId, boolean incoming) {}
    default void onFileState(String id, FileState state, String detail) {}
}
