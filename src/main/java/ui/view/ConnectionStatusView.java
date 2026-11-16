package ui.view;

import client.ConnectionState;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.Circle;
import javafx.scene.layout.HBox;

/** Renders connection status without owning sockets, tasks or session state. */
public final class ConnectionStatusView {
    private final Label label;
    private final Circle dot;
    private final Button reconnect;

    private final HBox banner;
    private final Label bannerLabel;
    private final Button bannerReconnect;

    public ConnectionStatusView(Label label, Circle dot, Button reconnect,
            HBox banner, Label bannerLabel, Button bannerReconnect) {
        this.label = label;
        this.dot = dot;
        this.reconnect = reconnect;
        this.banner = banner;
        this.bannerLabel = bannerLabel;
        this.bannerReconnect = bannerReconnect;
    }

    public void render(ConnectionState state, boolean showServerNotice) {
        boolean online = state == ConnectionState.ONLINE;
        boolean busy = state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING;
        reconnect.setVisible(!online);
        reconnect.setManaged(!online);
        reconnect.setDisable(busy || state == ConnectionState.CLOSED);
        boolean showBanner = showServerNotice
                && (state == ConnectionState.SERVER_OFFLINE || state == ConnectionState.RECONNECTING);
        banner.setVisible(showBanner);
        banner.setManaged(showBanner);
        bannerReconnect.setDisable(busy || state == ConnectionState.CLOSED);
        bannerLabel.setTooltip(null);
        bannerLabel.setText(state == ConnectionState.RECONNECTING
                ? "Reconnecting to the server..."
                : "Disconnected from the server. Reconnect to use communities and find new peers.");
        label.setText(switch (state) {
            case ONLINE -> "Online";
            case CONNECTING -> "Signing in...";
            case RECONNECTING -> "Reconnecting...";
            case SERVER_OFFLINE -> "Disconnected from server";
            case CLOSED -> "Signed out";
        });
        label.setStyle("-fx-font-size: 10px; -fx-text-fill: " + (online ? "#10B981;" : "#D97706;"));
        dot.getStyleClass().setAll(online ? "status-dot-online" : "status-dot-offline");
        label.setTooltip(new Tooltip(online ? "Connected to server"
                : "The server connection and each peer connection are monitored separately."));
    }

    public void showReconnectError(String reason) {
        label.setText("Unable to connect to server");
        bannerLabel.setText("Unable to connect to the server. Please try again.");
        bannerLabel.setTooltip(new Tooltip(reason));
        label.setTooltip(new Tooltip(reason));
    }
}
