package ui.view;

import client.ConnectionState;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.Circle;

/** Renders connection status without owning sockets, tasks or session state. */
public final class ConnectionStatusView {
    private final Label label;
    private final Circle dot;
    private final Button reconnect;

    public ConnectionStatusView(Label label, Circle dot, Button reconnect) {
        this.label = label;
        this.dot = dot;
        this.reconnect = reconnect;
    }

    public void render(ConnectionState state) {
        boolean online = state == ConnectionState.ONLINE;
        boolean busy = state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING;
        reconnect.setVisible(!online);
        reconnect.setManaged(!online);
        reconnect.setDisable(busy || state == ConnectionState.CLOSED);
        label.setText(switch (state) {
            case ONLINE -> "Online";
            case CONNECTING -> "Đang đăng nhập...";
            case RECONNECTING -> "Đang kết nối lại...";
            case SERVER_OFFLINE -> "Mất kết nối server";
            case CLOSED -> "Đã đăng xuất";
        });
        label.setStyle("-fx-font-size: 10px; -fx-text-fill: " + (online ? "#10B981;" : "#D97706;"));
        dot.getStyleClass().setAll(online ? "status-dot-online" : "status-dot-offline");
        label.setTooltip(new Tooltip(online ? "Đã đăng nhập server"
                : "Chat/file P2P còn sống vẫn dùng được. Community và tìm peer mới cần server."));
    }

    public void showReconnectError(String reason) {
        label.setText("Kết nối lại thất bại");
        label.setTooltip(new Tooltip(reason));
    }
}
