package ui.adapter;

import java.util.List;
import ui.model.CommunityModel;
import ui.model.FileOfferModel;

public interface ClientEventListener {
    default void onLoginSuccess(String username) {}
    default void onLoginFailed(String reason) {}
    default void onUserListUpdated(List<String> onlineUsers) {}
    default void onPeerConnected(String peerUsername) {}
    default void onPeerConnectionFailed(String peerUsername, String reason) {}
    default void onMessageReceived(String peerUsername, String message) {}
    default void onBroadcastMessageReceived(String communityId, String sender, String message) {}
    default void onCommunityListUpdated(List<CommunityModel> communities) {}
    default void onCommunityJoined(String communityId, String communityName) {}
    default void onCommunityLeft(String communityId) {}
    default void onFileOfferReceived(FileOfferModel offer) {}
    default void onPeerDisconnected(String peerUsername) {}
    default void onConnectionClosed() {}
    default void onSystemNotification(String message, boolean isError) {}
}
