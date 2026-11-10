package ui.adapter;

import java.util.List;
import ui.model.FileOfferModel;

public interface ClientEventListener {
    void onLoginSuccess(String username);
    void onLoginFailed(String reason);
    void onUserListUpdated(List<String> onlineUsers);
    void onPeerConnected(String peerUsername);
    void onPeerOffline(String peerUsername);
    void onPeerConnectionFailed(String peerUsername, String reason);
    void onMessageReceived(String peerUsername, String message);
    void onFileOfferReceived(FileOfferModel offer);
    void onFileOfferStateChanged(String offerNumber, String statusMessage);
    void onFileTransferCompleted(String fileName, String detail, boolean isSent);
    void onFileTransferError(String error);
    void onPeerDisconnected(String peerUsername);
    void onConnectionClosed();
    void onSystemNotification(String message, boolean isError);
}
