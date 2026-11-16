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
                ? "Đang kết nối lại tới server..."
                : "Mất kết nối tới server. Kết nối lại để dùng community và tìm peer mới.");
        label.setText(switch (state) {
            case ONLINE -> "Online";
            case CONNECTING -> "Đang đăng nhập...";
            case RECONNECTING -> "Đang kết nối lại...";
            case SERVER_OFFLINE -> "Mất kết nối tới server";
            case CLOSED -> "Đã đăng xuất";
        });
        label.setStyle("-fx-font-size: 10px; -fx-text-fill: " + (online ? "#10B981;" : "#D97706;"));
        dot.getStyleClass().setAll(online ? "status-dot-online" : "status-dot-offline");
        label.setTooltip(new Tooltip(online ? "Đã đăng nhập server"
                : "Trạng thái kết nối tới server được theo dõi riêng với từng kết nối P2P."));
    }

    public void showReconnectError(String reason) {
        label.setText("Chưa thể kết nối tới server");
        bannerLabel.setText("Chưa thể kết nối tới server. Vui lòng thử lại.");
        bannerLabel.setTooltip(new Tooltip(reason));
        label.setTooltip(new Tooltip(reason));
    }
}
