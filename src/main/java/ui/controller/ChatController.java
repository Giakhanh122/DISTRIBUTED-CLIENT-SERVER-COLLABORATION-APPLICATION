package ui.controller;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import ui.adapter.ChatClientAdapter;
import ui.adapter.ClientEventListener;
import ui.model.ChatMessageModel;
import ui.model.FileOfferModel;
import ui.model.UserModel;
import ui.util.IconUtils;
import ui.util.JavaFxUtils;

public class ChatController implements ClientEventListener {

    // --- Sidebar Controls ---
    @FXML private StackPane brandLogoContainer;
    @FXML private Button refreshUsersBtn;
    @FXML private StackPane currentUserAvatar;
    @FXML private Label currentUserInitials;
    @FXML private Label currentUsernameLabel;
    @FXML private Label connectionStatusLabel;
    @FXML private TextField searchField;
    @FXML private Label userSectionTitle;
    @FXML private ListView<UserModel> usersListView;
    @FXML private Button logoutBtn;
    @FXML private Label serverAddressLabel;

    // --- Chat Header Controls ---
    @FXML private HBox chatHeader;
    @FXML private StackPane peerAvatar;
    @FXML private Label peerInitials;
    @FXML private Label peerUsernameLabel;
    @FXML private Circle peerStatusDot;
    @FXML private Label peerInfoLabel;
    @FXML private Button sendFileHeaderBtn;

    // --- Message Area Controls ---
    @FXML private VBox emptyStateView;
    @FXML private StackPane emptyStateIconContainer;
    @FXML private ScrollPane messageScrollPane;
    @FXML private VBox messagesContainer;

    // --- Input Area Controls ---
    @FXML private HBox inputContainer;
    @FXML private Button attachBtn;
    @FXML private TextField messageInput;
    @FXML private Button sendBtn;

    private final ChatClientAdapter adapter = ChatClientAdapter.getInstance();

    private final ObservableList<UserModel> allUsers = FXCollections.observableArrayList();
    private FilteredList<UserModel> filteredUsers;

    // Map of conversation histories: peerUsername -> list of messages
    private final Map<String, ObservableList<ChatMessageModel>> conversations = new ConcurrentHashMap<>();

    private UserModel activePeer = null;

    @FXML
    public void initialize() {
        adapter.addListener(this);

        setupIcons();
        setupCurrentUserDisplay();
        setupUserListView();

        // Load any cached user list immediately
        List<String> cached = adapter.getLastKnownOnlineUsers();
        if (!cached.isEmpty()) {
            onUserListUpdated(cached);
        }

        // Initial request for users
        adapter.requestUserList();
    }

    private void setupIcons() {
        brandLogoContainer.getChildren().add(IconUtils.createIconNode(IconUtils.APP_LOGO, 24, "#6C8CFF"));

        refreshUsersBtn.setGraphic(IconUtils.createIconNode(IconUtils.REFRESH, 15, "#9299A8"));
        refreshUsersBtn.setTooltip(new Tooltip("Refresh users list"));

        logoutBtn.setGraphic(IconUtils.createIconNode(IconUtils.LOGOUT, 14, "#9299A8"));

        emptyStateIconContainer.getChildren().add(IconUtils.createIconNode(IconUtils.CHAT_BUBBLE, 52, "#2A303E"));

        attachBtn.setGraphic(IconUtils.createIconNode(IconUtils.PAPERCLIP, 18, "#9299A8"));
        attachBtn.setTooltip(new Tooltip("Attach and send file"));

        sendBtn.setGraphic(IconUtils.createIconNode(IconUtils.SEND, 16, "#FFFFFF"));

        sendFileHeaderBtn.setGraphic(IconUtils.createIconNode(IconUtils.FILE, 14, "#6C8CFF"));
    }

    private void setupCurrentUserDisplay() {
        String username = adapter.getCurrentUsername();
        currentUsernameLabel.setText(username != null ? username : "User");

        String initials = username != null && !username.isBlank()
                ? (username.length() <= 2 ? username.toUpperCase() : username.substring(0, 2).toUpperCase())
                : "?";
        currentUserInitials.setText(initials);
        currentUserAvatar.setStyle("-fx-background-color: " + JavaFxUtils.getAvatarColor(username) + ";");

        serverAddressLabel.setText(adapter.getServerHost() + ":" + adapter.getServerPort());
    }

    private void setupUserListView() {
        Label placeholder = new Label("No other users online");
        placeholder.setStyle("-fx-text-fill: #5E6678; -fx-font-size: 12px;");
        usersListView.setPlaceholder(placeholder);

        filteredUsers = new FilteredList<>(allUsers, p -> true);
        usersListView.setItems(filteredUsers);

        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            filteredUsers.setPredicate(user -> {
                if (newVal == null || newVal.isBlank()) return true;
                return user.getUsername().toLowerCase().contains(newVal.trim().toLowerCase());
            });
        });

        usersListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(UserModel user, boolean empty) {
                super.updateItem(user, empty);
                if (empty || user == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    HBox itemBox = new HBox(10);
                    itemBox.setAlignment(Pos.CENTER_LEFT);
                    itemBox.setPadding(new Insets(6, 8, 6, 8));
                    itemBox.getStyleClass().add("user-item");

                    // Avatar Circle
                    StackPane avatar = new StackPane();
                    avatar.getStyleClass().add("avatar-circle");
                    avatar.setMinSize(32, 32);
                    avatar.setPrefSize(32, 32);
                    avatar.setMaxSize(32, 32);
                    avatar.setStyle("-fx-background-color: " + JavaFxUtils.getAvatarColor(user.getUsername()) + ";");

                    Label initialsLabel = new Label(user.getInitials());
                    initialsLabel.getStyleClass().add("avatar-text");
                    initialsLabel.setStyle("-fx-font-size: 11px;");
                    avatar.getChildren().add(initialsLabel);

                    // Username & status
                    VBox nameBox = new VBox(2);
                    nameBox.setAlignment(Pos.CENTER_LEFT);
                    HBox.setHgrow(nameBox, Priority.ALWAYS);

                    Label nameLabel = new Label(user.getUsername());
                    nameLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #F1F3F8; -fx-font-size: 13px;");

                    HBox statusRow = new HBox(4);
                    statusRow.setAlignment(Pos.CENTER_LEFT);
                    Circle dot = new Circle(3);
                    dot.getStyleClass().add(user.isOnline() ? "status-dot-online" : "status-dot-offline");
                    Label statusLbl = new Label(user.getStatusMessage());
                    statusLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #9299A8;");
                    statusRow.getChildren().addAll(dot, statusLbl);

                    nameBox.getChildren().addAll(nameLabel, statusRow);

                    itemBox.getChildren().addAll(avatar, nameBox);

                    // Unread badge if any
                    if (user.getUnreadCount() > 0) {
                        Label unread = new Label(Integer.toString(user.getUnreadCount()));
                        unread.getStyleClass().add("unread-badge");
                        itemBox.getChildren().add(unread);
                    }

                    setGraphic(itemBox);
                    setText(null);
                }
            }
        });

        usersListView.getSelectionModel().selectedItemProperty().addListener((obs, oldUser, newUser) -> {
            if (newUser != null) {
                selectPeerUser(newUser);
            }
        });
    }

    private void selectPeerUser(UserModel user) {
        this.activePeer = user;
        user.clearUnread();
        usersListView.refresh();

        // Notify backend adapter
        adapter.selectPeer(user.getUsername());

        // Update Chat Header
        peerUsernameLabel.setText(user.getUsername());
        peerInitials.setText(user.getInitials());
        peerAvatar.setStyle("-fx-background-color: " + JavaFxUtils.getAvatarColor(user.getUsername()) + ";");
        peerStatusDot.setVisible(true);
        peerStatusDot.getStyleClass().clear();
        peerStatusDot.getStyleClass().add(user.isOnline() ? "status-dot-online" : "status-dot-offline");
        peerInfoLabel.setText(user.isOnline() ? "Direct P2P Session Active" : "User Offline");

        sendFileHeaderBtn.setVisible(true);
        sendFileHeaderBtn.setManaged(true);

        // Switch from empty state to conversation
        emptyStateView.setVisible(false);
        emptyStateView.setManaged(false);
        messageScrollPane.setVisible(true);
        messageScrollPane.setManaged(true);
        inputContainer.setDisable(!user.isOnline());
        messageInput.setPromptText("Message @" + user.getUsername() + "...");

        // Load conversation
        loadConversation(user.getUsername());

        Platform.runLater(() -> messageInput.requestFocus());
    }

    private void loadConversation(String peerUsername) {
        messagesContainer.getChildren().clear();
        ObservableList<ChatMessageModel> history = conversations.computeIfAbsent(
                peerUsername, k -> FXCollections.observableArrayList()
        );

        for (ChatMessageModel msg : history) {
            renderMessageNode(msg);
        }
        scrollToBottom();
    }

    @FXML
    private void handleSendMessage() {
        if (activePeer == null) return;
        String text = messageInput.getText().trim();
        if (text.isEmpty()) return;

        messageInput.clear();

        // Send via adapter
        adapter.sendChatMessage(activePeer.getUsername(), text);

        // Add to history
        ChatMessageModel msg = new ChatMessageModel(adapter.getCurrentUsername(), text, true);
        ObservableList<ChatMessageModel> history = conversations.computeIfAbsent(
                activePeer.getUsername(), k -> FXCollections.observableArrayList()
        );
        history.add(msg);
        renderMessageNode(msg);
        scrollToBottom();
    }

    @FXML
    private void handleAttachFile() {
        if (activePeer == null) return;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select File to Send to " + activePeer.getUsername());
        Stage stage = (Stage) attachBtn.getScene().getWindow();
        File file = chooser.showOpenDialog(stage);

        if (file != null) {
            if (file.length() > 100L * 1024 * 1024) {
                JavaFxUtils.showErrorAlert("File Too Large", "Maximum supported file size is 100 MiB.");
                return;
            }

            adapter.sendFile(activePeer.getUsername(), file);

            // Add outgoing offer card to chat
            FileOfferModel offer = new FileOfferModel(
                    "out-" + System.currentTimeMillis(),
                    adapter.getCurrentUsername(),
                    activePeer.getUsername(),
                    file.getName(),
                    file.length(),
                    "",
                    false
            );
            offer.setStatus(FileOfferModel.Status.PENDING);
            offer.setStatusText("Offered '" + file.getName() + "' to @" + activePeer.getUsername() + ". Waiting for decision...");

            ChatMessageModel msg = ChatMessageModel.file(adapter.getCurrentUsername(), offer, true);
            ObservableList<ChatMessageModel> history = conversations.computeIfAbsent(
                    activePeer.getUsername(), k -> FXCollections.observableArrayList()
            );
            history.add(msg);
            renderMessageNode(msg);
            scrollToBottom();
        }
    }

    @FXML
    private void handleRefreshUsers() {
        adapter.requestUserList();
    }

    @FXML
    private void handleLogout() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Are you sure you want to log out?", ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Log Out");
        confirm.setHeaderText(null);
        confirm.getDialogPane().setStyle("-fx-background-color: #181B22; -fx-text-fill: #F1F3F8;");
        confirm.showAndWait().ifPresent(res -> {
            if (res == ButtonType.YES) {
                adapter.logout();
                adapter.removeListener(this);

                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/login.fxml"));
                    Parent root = loader.load();
                    Stage stage = (Stage) logoutBtn.getScene().getWindow();
                    stage.setScene(new Scene(root, 850, 600));
                    stage.setTitle("PulseChat — Login");
                    stage.centerOnScreen();
                } catch (IOException e) {
                    JavaFxUtils.showErrorAlert("Navigation Error", "Failed to return to login: " + e.getMessage());
                }
            }
        });
    }

    // --- Message Node Rendering ---

    private void renderMessageNode(ChatMessageModel msg) {
        if (msg.isSystem()) {
            HBox systemRow = new HBox();
            systemRow.setAlignment(Pos.CENTER);
            Label sysLbl = new Label(msg.getContent());
            sysLbl.getStyleClass().add("bubble-system");
            systemRow.getChildren().add(sysLbl);
            messagesContainer.getChildren().add(systemRow);
            return;
        }

        if (msg.isFileOffer()) {
            renderFileOfferCard(msg);
            return;
        }

        // Standard Text Message Bubble
        VBox messageBubbleBox = new VBox(3);
        messageBubbleBox.setMaxWidth(480.0);

        Label contentLbl = new Label(msg.getContent());
        contentLbl.setWrapText(true);

        Label timeLbl = new Label(msg.getTimestamp());
        timeLbl.getStyleClass().add("bubble-timestamp");

        if (msg.isMine()) {
            // Sent Bubble (Right Aligned)
            contentLbl.getStyleClass().add("bubble-sent");
            contentLbl.getStyleClass().add("bubble-sent-text");
            messageBubbleBox.setAlignment(Pos.TOP_RIGHT);
            timeLbl.setAlignment(Pos.CENTER_RIGHT);
            messageBubbleBox.getChildren().addAll(contentLbl, timeLbl);

            HBox row = new HBox(messageBubbleBox);
            row.setAlignment(Pos.CENTER_RIGHT);
            messagesContainer.getChildren().add(row);
        } else {
            // Received Bubble (Left Aligned with Avatar)
            contentLbl.getStyleClass().add("bubble-received");
            contentLbl.getStyleClass().add("bubble-received-text");

            Label senderLbl = new Label(msg.getSender());
            senderLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #9299A8;");

            messageBubbleBox.setAlignment(Pos.TOP_LEFT);
            messageBubbleBox.getChildren().addAll(senderLbl, contentLbl, timeLbl);

            HBox row = new HBox(8);
            row.setAlignment(Pos.TOP_LEFT);

            StackPane smallAvatar = new StackPane();
            smallAvatar.setMinSize(28, 28);
            smallAvatar.setPrefSize(28, 28);
            smallAvatar.setMaxSize(28, 28);
            smallAvatar.getStyleClass().add("avatar-circle");
            smallAvatar.setStyle("-fx-background-color: " + JavaFxUtils.getAvatarColor(msg.getSender()) + ";");

            String inits = msg.getSender().length() <= 2 ? msg.getSender().toUpperCase() : msg.getSender().substring(0, 2).toUpperCase();
            Label initsLbl = new Label(inits);
            initsLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: white; -fx-font-weight: bold;");
            smallAvatar.getChildren().add(initsLbl);

            row.getChildren().addAll(smallAvatar, messageBubbleBox);
            messagesContainer.getChildren().add(row);
        }
    }

    private void renderFileOfferCard(ChatMessageModel msg) {
        FileOfferModel offer = msg.getFileOffer();
        if (offer == null) return;

        VBox card = new VBox(10);
        card.getStyleClass().add("file-card");
        card.setMaxWidth(420.0);

        // Header: File icon + File Name + Size
        HBox topRow = new HBox(10);
        topRow.setAlignment(Pos.CENTER_LEFT);

        StackPane fileIcon = IconUtils.createIconNode(IconUtils.FILE, 26, "#6C8CFF");
        VBox fileInfo = new VBox(2);
        Label nameLbl = new Label(offer.getFileName());
        nameLbl.getStyleClass().add("file-card-title");
        Label metaLbl = new Label(offer.getFormattedSize() + (offer.getChecksum().isEmpty() ? "" : " • SHA-256: " + offer.getChecksum().substring(0, Math.min(10, offer.getChecksum().length())) + "..."));
        metaLbl.getStyleClass().add("file-card-meta");
        fileInfo.getChildren().addAll(nameLbl, metaLbl);
        topRow.getChildren().addAll(fileIcon, fileInfo);

        // Status description
        Label statusLbl = new Label(offer.getStatusText());
        statusLbl.textProperty().bind(offer.statusTextProperty());
        statusLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #9299A8;");

        // Progress bar (bound to offer progress if transferring)
        ProgressBar pb = new ProgressBar(0.0);
        pb.progressProperty().bind(offer.progressProperty());
        pb.setMaxWidth(Double.MAX_VALUE);
        pb.visibleProperty().bind(offer.statusProperty().isEqualTo(FileOfferModel.Status.TRANSFERRING));
        pb.managedProperty().bind(pb.visibleProperty());

        card.getChildren().addAll(topRow, statusLbl, pb);

        // Action Buttons for incoming pending offer
        if (offer.isIncoming() && offer.getStatus() == FileOfferModel.Status.PENDING) {
            HBox actions = new HBox(8);
            actions.setAlignment(Pos.CENTER_RIGHT);

            Button acceptBtn = new Button("Accept");
            acceptBtn.getStyleClass().add("btn-success");
            acceptBtn.setGraphic(IconUtils.createIconNode(IconUtils.CHECK, 12, "#FFFFFF"));

            Button rejectBtn = new Button("Reject");
            rejectBtn.getStyleClass().add("btn-danger");
            rejectBtn.setGraphic(IconUtils.createIconNode(IconUtils.X, 12, "#FFFFFF"));

            acceptBtn.setOnAction(e -> {
                adapter.acceptFileOffer(offer.getOfferNumber());
                offer.setStatus(FileOfferModel.Status.ACCEPTED);
                actions.setVisible(false);
                actions.setManaged(false);
            });

            rejectBtn.setOnAction(e -> {
                adapter.rejectFileOffer(offer.getOfferNumber());
                offer.setStatus(FileOfferModel.Status.REJECTED);
                actions.setVisible(false);
                actions.setManaged(false);
            });

            actions.getChildren().addAll(rejectBtn, acceptBtn);
            card.getChildren().add(actions);
        }

        HBox outer = new HBox(card);
        outer.setAlignment(msg.isMine() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        messagesContainer.getChildren().add(outer);
    }

    private void scrollToBottom() {
        Platform.runLater(() -> messageScrollPane.setVvalue(1.0));
    }

    // --- ClientEventListener Callbacks ---

    @Override
    public void onUserListUpdated(List<String> onlineUsers) {
        String current = adapter.getCurrentUsername();

        List<UserModel> updated = new ArrayList<>();
        for (String u : onlineUsers) {
            if (u.equalsIgnoreCase(current)) continue;

            // Preserve existing user state if already present
            UserModel existing = null;
            for (UserModel m : allUsers) {
                if (m.getUsername().equals(u)) {
                    existing = m;
                    break;
                }
            }
            if (existing != null) {
                existing.setOnline(true);
                updated.add(existing);
            } else {
                updated.add(new UserModel(u, true));
            }
        }

        allUsers.setAll(updated);
        userSectionTitle.setText("ONLINE USERS (" + updated.size() + ")");

        if (activePeer != null) {
            for (UserModel m : allUsers) {
                if (m.getUsername().equals(activePeer.getUsername())) {
                    usersListView.getSelectionModel().select(m);
                    break;
                }
            }
        }
    }

    @Override
    public void onPeerConnected(String peerUsername) {
        if (activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            peerInfoLabel.setText("Direct P2P Session Active");
            peerStatusDot.setVisible(true);
            peerStatusDot.getStyleClass().clear();
            peerStatusDot.getStyleClass().add("status-dot-online");
            inputContainer.setDisable(false);
        }
    }

    @Override
    public void onPeerOffline(String peerUsername) {
        if (activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            peerInfoLabel.setText("Peer is currently offline");
            peerStatusDot.setVisible(true);
            peerStatusDot.getStyleClass().clear();
            peerStatusDot.getStyleClass().add("status-dot-offline");
            inputContainer.setDisable(true);
        }
    }

    @Override
    public void onPeerConnectionFailed(String peerUsername, String reason) {
        if (activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            peerInfoLabel.setText("Connection failed: " + reason);
        }
    }

    @Override
    public void onMessageReceived(String peerUsername, String message) {
        ChatMessageModel msg = new ChatMessageModel(peerUsername, message, false);
        ObservableList<ChatMessageModel> history = conversations.computeIfAbsent(
                peerUsername, k -> FXCollections.observableArrayList()
        );
        history.add(msg);

        if (activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            renderMessageNode(msg);
            scrollToBottom();
        } else {
            // Increment unread for this peer
            for (UserModel u : allUsers) {
                if (u.getUsername().equals(peerUsername)) {
                    u.incrementUnread();
                    usersListView.refresh();
                    break;
                }
            }
        }
    }

    @Override
    public void onFileOfferReceived(FileOfferModel offer) {
        String peer = offer.isIncoming() ? offer.getSender() : offer.getReceiver();
        ChatMessageModel msg = ChatMessageModel.file(offer.getSender(), offer, !offer.isIncoming());

        ObservableList<ChatMessageModel> history = conversations.computeIfAbsent(
                peer, k -> FXCollections.observableArrayList()
        );
        history.add(msg);

        if (activePeer != null && activePeer.getUsername().equals(peer)) {
            renderMessageNode(msg);
            scrollToBottom();
        } else {
            for (UserModel u : allUsers) {
                if (u.getUsername().equals(peer)) {
                    u.incrementUnread();
                    usersListView.refresh();
                    break;
                }
            }
        }
    }

    @Override
    public void onFileOfferStateChanged(String offerNumber, String statusMessage) {
        FileOfferModel offer = adapter.getFileOffer(offerNumber);
        if (offer != null) {
            offer.setStatusText(statusMessage);
        }
    }

    @Override
    public void onFileTransferCompleted(String fileName, String detail, boolean isSent) {
        String note = isSent ? "Sent '" + fileName + "' successfully." : "Received '" + fileName + "' (" + detail + ")";
        if (activePeer != null) {
            ChatMessageModel sys = ChatMessageModel.system(note);
            ObservableList<ChatMessageModel> history = conversations.computeIfAbsent(
                    activePeer.getUsername(), k -> FXCollections.observableArrayList()
            );
            history.add(sys);
            renderMessageNode(sys);
            scrollToBottom();
        }
    }

    @Override
    public void onFileTransferError(String error) {
        if (activePeer != null) {
            ChatMessageModel sys = ChatMessageModel.system("File Transfer Error: " + error);
            ObservableList<ChatMessageModel> history = conversations.computeIfAbsent(
                    activePeer.getUsername(), k -> FXCollections.observableArrayList()
            );
            history.add(sys);
            renderMessageNode(sys);
            scrollToBottom();
        }
    }

    @Override
    public void onPeerDisconnected(String peerUsername) {
        if (activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            peerInfoLabel.setText("Peer disconnected");
            inputContainer.setDisable(true);
        }
    }

    @Override
    public void onConnectionClosed() {
        JavaFxUtils.showErrorAlert("Disconnected", "Server connection was closed.");
    }

    @Override
    public void onSystemNotification(String message, boolean isError) {
        // Can optionally log to status or active chat
    }

    @Override public void onLoginSuccess(String username) {}
    @Override public void onLoginFailed(String reason) {}
}
