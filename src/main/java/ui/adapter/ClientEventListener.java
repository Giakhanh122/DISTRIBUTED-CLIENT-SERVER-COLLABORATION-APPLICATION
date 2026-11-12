package ui.adapter;

import java.util.List;
import ui.model.CommunityModel;
import ui.model.FileOfferModel;

public interface ClientEventListener {
    void onLoginSuccess(String username);
    void onLoginFailed(String reason);
    void onUserListUpdated(List<String> onlineUsers);
    void onPeerConnected(String peerUsername);
    void onPeerOffline(String peerUsername);
    void onPeerConnectionFailed(String peerUsername, String reason);
    void onMessageReceived(String peerUsername, String message);
    void onBroadcastMessageReceived(String communityId, String sender, String message);
    void onCommunityListUpdated(List<CommunityModel> communities);
    void onCommunityJoined(String communityId, String communityName);
    void onCommunityLeft(String communityId);
    void onFileOfferReceived(FileOfferModel offer);
    void onFileOfferStateChanged(String offerNumber, String statusMessage);
    void onFileTransferCompleted(String fileName, String detail, boolean isSent);
    void onFileTransferError(String error);
    void onPeerDisconnected(String peerUsername);
    void onConnectionClosed();
    void onSystemNotification(String message, boolean isError);
}
