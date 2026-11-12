package ui.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import ui.model.CommunityModel;
import ui.model.UserModel;
import ui.util.JavaFxUtils;

public final class ConversationCells {
    private ConversationCells() {}

    public static ListCell<CommunityModel> community() {
        return new ListCell<>() {
            @Override
            protected void updateItem(CommunityModel comm, boolean empty) {
                super.updateItem(comm, empty);
                if (empty || comm == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    HBox box = new HBox(10);
                    box.setAlignment(Pos.CENTER_LEFT);
                    box.setPadding(new Insets(6, 8, 6, 8));
                    box.getStyleClass().add("community-item");

                    StackPane avatar = new StackPane();
                    avatar.getStyleClass().add("avatar-circle");
                    avatar.setMinSize(30, 30);
                    avatar.setPrefSize(30, 30);
                    avatar.setMaxSize(30, 30);
                    avatar.setStyle("-fx-background-color: " + JavaFxUtils.getAvatarColor(comm.getId()) + ";");

                    Label init = new Label(comm.getInitials());
                    init.getStyleClass().add("avatar-text");
                    init.setStyle("-fx-font-size: 10px;");
                    avatar.getChildren().add(init);

                    VBox info = new VBox(2);
                    info.setAlignment(Pos.CENTER_LEFT);
                    HBox.setHgrow(info, Priority.ALWAYS);

                    Label nameLbl = new Label(comm.getName());
                    nameLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #0F172A; -fx-font-size: 13px;");

                    String statusText = comm.getMemberCount() + " members" + (comm.isJoined() ? " • Joined" : " • Click to join");
                    Label meta = new Label(statusText);
                    meta.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (comm.isJoined() ? "#10B981;" : "#71717A;"));
                    info.getChildren().addAll(nameLbl, meta);

                    box.getChildren().addAll(avatar, info);

                    if (comm.getUnreadCount() > 0) {
                        Label unread = new Label(String.valueOf(comm.getUnreadCount()));
                        unread.getStyleClass().add("unread-badge");
                        box.getChildren().add(unread);
                    }

                    setGraphic(box);
                    setText(null);
                }
            }
        };
    }

    public static ListCell<UserModel> user() {
        return new ListCell<>() {
            @Override
            protected void updateItem(UserModel user, boolean empty) {
                super.updateItem(user, empty);
                if (empty || user == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    HBox box = new HBox(10);
                    box.setAlignment(Pos.CENTER_LEFT);
                    box.setPadding(new Insets(6, 8, 6, 8));
                    box.getStyleClass().add("user-item");

                    StackPane avatar = new StackPane();
                    avatar.getStyleClass().add("avatar-circle");
                    avatar.setMinSize(30, 30);
                    avatar.setPrefSize(30, 30);
                    avatar.setMaxSize(30, 30);
                    avatar.setStyle("-fx-background-color: " + JavaFxUtils.getAvatarColor(user.getUsername()) + ";");

                    Label init = new Label(user.getInitials());
                    init.getStyleClass().add("avatar-text");
                    init.setStyle("-fx-font-size: 10px;");
                    avatar.getChildren().add(init);

                    VBox info = new VBox(2);
                    info.setAlignment(Pos.CENTER_LEFT);
                    HBox.setHgrow(info, Priority.ALWAYS);

                    Label name = new Label(user.getUsername());
                    name.setStyle("-fx-font-weight: bold; -fx-text-fill: #0F172A; -fx-font-size: 13px;");

                    HBox stat = new HBox(4);
                    stat.setAlignment(Pos.CENTER_LEFT);
                    Circle dot = new Circle(3);
                    dot.getStyleClass().add(user.isOnline() ? "status-dot-online" : "status-dot-offline");
                    Label stText = new Label(user.getStatusMessage());
                    stText.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748B;");
                    stat.getChildren().addAll(dot, stText);

                    info.getChildren().addAll(name, stat);
                    box.getChildren().addAll(avatar, info);

                    if (user.getUnreadCount() > 0) {
                        Label unread = new Label(String.valueOf(user.getUnreadCount()));
                        unread.getStyleClass().add("unread-badge");
                        box.getChildren().add(unread);
                    }

                    setGraphic(box);
                    setText(null);
                }
            }
        };
    }
}
