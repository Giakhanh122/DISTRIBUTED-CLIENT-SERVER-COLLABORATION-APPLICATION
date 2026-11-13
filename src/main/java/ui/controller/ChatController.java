package ui.controller;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import ui.adapter.ChatClientAdapter;
import ui.adapter.ClientEventListener;
import ui.model.ChatMessageModel;
import ui.model.CommunityModel;
import ui.model.ConversationStore;
import ui.model.FileOfferModel;
import ui.model.UserModel;
import ui.util.IconUtils;
import ui.util.JavaFxUtils;
import ui.util.WindowDragHelper;
import ui.view.ChatMessageView;
import ui.view.ConnectionStatusView;
import ui.view.ConversationCells;

public class ChatController implements ClientEventListener {

    @FXML private HBox titleBar;
    @FXML private Label titleBarLabel;
    @FXML private Button minimizeBtn;
    @FXML private Button maximizeBtn;
    @FXML private Button closeBtn;

    @FXML private Button homeBtn;
    @FXML private Button railCommunitiesBtn;
    @FXML private Button railExploreBtn;
    @FXML private Button railDirectBtn;
    @FXML private Button addCommunityBtn;

    @FXML private TextField searchField;
    @FXML private Label sidebarSectionTitle;
    @FXML private ListView<CommunityModel> communitiesListView;
    @FXML private ListView<UserModel> usersListView;
    @FXML private StackPane currentUserAvatar;
    @FXML private Label currentUserInitials;
    @FXML private Label currentUsernameLabel;
    @FXML private Label connectionStatusLabel;
    @FXML private Circle connectionStatusDot;
    @FXML private Button reconnectBtn;
    @FXML private Button logoutBtn;

    @FXML private HBox chatHeader;
    @FXML private StackPane targetAvatar;
    @FXML private Label targetInitials;
    @FXML private Label targetNameLabel;
    @FXML private Circle targetStatusDot;
    @FXML private Label targetMetaLabel;
    @FXML private Button joinLeaveCommunityBtn;

    @FXML private VBox emptyStateView;
    @FXML private StackPane emptyStateIconContainer;
    @FXML private ScrollPane messageScrollPane;
    @FXML private VBox messagesContainer;

    @FXML private HBox inputContainer;
    @FXML private Button attachBtn;
    @FXML private TextField messageInput;
    @FXML private Button sendBtn;

    private final ChatClientAdapter adapter = ChatClientAdapter.getInstance();

    private final ObservableList<CommunityModel> allCommunities = FXCollections.observableArrayList();
    private FilteredList<CommunityModel> filteredCommunities;

    private final ObservableList<UserModel> allUsers = FXCollections.observableArrayList();
    private FilteredList<UserModel> filteredUsers;

    private final ConversationStore conversations = new ConversationStore();

    private enum Mode { COMMUNITY, EXPLORE, DIRECT }
    private Mode currentMode = Mode.COMMUNITY;

    private ChatMessageView messageView;
    private ConnectionStatusView connectionView;

    private CommunityModel activeCommunity = null;
    private UserModel activePeer = null;

    @FXML
    public void initialize() {
        connectionView = new ConnectionStatusView(connectionStatusLabel, connectionStatusDot, reconnectBtn);
        messageView = new ChatMessageView(messagesContainer, adapter::acceptFileOffer, adapter::rejectFileOffer);

        setupTitleBar();
        setupCurrentUserDisplay();
        setupLists();
        setupIcons();
        handleSelectCommunitiesTab();

        onUserListUpdated(adapter.getLastKnownOnlineUsers());
        onCommunityListUpdated(adapter.getLastKnownCommunities());

        adapter.requestUserList();
        adapter.requestCommunityList();
        adapter.addListener(this);
        updateConnectionState();
    }

    private void setupTitleBar() {
        WindowDragHelper.makeDraggable(titleBar);
        String username = adapter.getCurrentUsername();
        titleBarLabel.setText("DL-Chat - " + (username != null ? username : "Portal"));
    }

    private void setupIcons() {
        homeBtn.setTooltip(new Tooltip("DL-Chat"));
        railCommunitiesBtn.setTooltip(new Tooltip("My Communities (Joined)"));
        railExploreBtn.setTooltip(new Tooltip("Explore Communities (Find & Join)"));
        railDirectBtn.setTooltip(new Tooltip("Direct Messages (P2P)"));
        addCommunityBtn.setTooltip(new Tooltip("Create Community"));

        logoutBtn.setGraphic(IconUtils.createIconNode(IconUtils.LOGOUT, 14, "#71717A"));
        logoutBtn.setTooltip(new Tooltip("Log Out"));

        emptyStateIconContainer.getChildren().add(IconUtils.createIconNode(IconUtils.CHAT_BUBBLE, 44, "#D4D4D8"));

        attachBtn.setGraphic(IconUtils.createIconNode(IconUtils.PAPERCLIP, 16, "#71717A"));
        attachBtn.setTooltip(new Tooltip("Attach file to send"));

        sendBtn.setGraphic(IconUtils.createIconNode(IconUtils.SEND, 14, "#09090B"));
    }

    private void setupCurrentUserDisplay() {
        String username = adapter.getCurrentUsername();
        currentUsernameLabel.setText(username != null ? username : "User");

        String initials = username != null && !username.isBlank()
                ? (username.length() <= 2 ? username.toUpperCase() : username.substring(0, 2).toUpperCase())
                : "ME";
        currentUserInitials.setText(initials);
        currentUserAvatar.setStyle("-fx-background-color: " + JavaFxUtils.getAvatarColor(username) + ";");
    }

    private void setupLists() {
        VBox emptyCommBox = new VBox(8);
        emptyCommBox.setAlignment(Pos.CENTER);
        Label noComm = new Label("No joined communities");
        noComm.setStyle("-fx-text-fill: #71717A; -fx-font-size: 12px; -fx-font-weight: bold;");
        Button explorePromptBtn = new Button("Explore Communities");
        explorePromptBtn.getStyleClass().add("btn-secondary");
        explorePromptBtn.setStyle("-fx-font-size: 11px; -fx-padding: 5px 12px; -fx-cursor: hand;");
        explorePromptBtn.setOnAction(e -> handleSelectExploreTab());
        emptyCommBox.getChildren().addAll(noComm, explorePromptBtn);
        communitiesListView.setPlaceholder(emptyCommBox);

        filteredCommunities = new FilteredList<>(allCommunities, CommunityModel::isJoined);
        communitiesListView.setItems(filteredCommunities);

        communitiesListView.setCellFactory(list -> ConversationCells.community());

        communitiesListView.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && currentMode != Mode.DIRECT) {
                selectCommunity(newV);
            }
        });

        Label noUsers = new Label("No other users online");
        noUsers.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 12px;");
        usersListView.setPlaceholder(noUsers);

        filteredUsers = new FilteredList<>(allUsers, p -> true);
        usersListView.setItems(filteredUsers);

        usersListView.setCellFactory(list -> ConversationCells.user());

        usersListView.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && currentMode == Mode.DIRECT) {
                selectPeerUser(newV);
            }
        });

        searchField.textProperty().addListener((obs, oldV, newV) -> {
            applyFilter(newV);
        });
    }

    private void applyFilter(String query) {
        if (filteredCommunities == null || filteredUsers == null) {
            return;
        }
        String q = (query == null) ? "" : query.trim().toLowerCase();
        if (currentMode == Mode.COMMUNITY) {
            if (q.isEmpty()) {
                filteredCommunities.setPredicate(CommunityModel::isJoined);
            } else {
                filteredCommunities.setPredicate(c -> c.getName().toLowerCase().contains(q));
            }
        } else if (currentMode == Mode.EXPLORE) {
            if (q.isEmpty()) {
                filteredCommunities.setPredicate(p -> true);
            } else {
                filteredCommunities.setPredicate(c -> c.getName().toLowerCase().contains(q));
            }
        }
        filteredUsers.setPredicate(u -> q.isEmpty() || u.getUsername().toLowerCase().contains(q));
    }

    private void showEmptyChatState() {
        if (currentMode == Mode.DIRECT) activePeer = null;
        else activeCommunity = null;
        emptyStateView.setVisible(true);
        emptyStateView.setManaged(true);
        messageScrollPane.setVisible(false);
        messageScrollPane.setManaged(false);
        joinLeaveCommunityBtn.setVisible(false);
        joinLeaveCommunityBtn.setManaged(false);
        targetNameLabel.setText("Select a conversation");
        targetInitials.setText("");
        targetAvatar.setStyle("-fx-background-color: transparent;");
        targetStatusDot.setVisible(false);
        targetMetaLabel.setText("No active conversation");
        inputContainer.setDisable(true);
        messageInput.clear();
        messageInput.setPromptText("Select a community or user...");
    }

    @FXML
    private void handleSelectCommunitiesTab() { selectNavigationTab(Mode.COMMUNITY); }

    @FXML
    private void handleSelectExploreTab() { selectNavigationTab(Mode.EXPLORE); }

    @FXML
    private void handleSelectDirectTab() { selectNavigationTab(Mode.DIRECT); }

    private void selectNavigationTab(Mode mode) {
        currentMode = mode;
        updateRailButton(railCommunitiesBtn, Mode.COMMUNITY, IconUtils.CHAT_BUBBLE);
        updateRailButton(railExploreBtn, Mode.EXPLORE, IconUtils.COMPASS);
        updateRailButton(railDirectBtn, Mode.DIRECT, IconUtils.USER);
        sidebarSectionTitle.setText(switch (mode) {
            case COMMUNITY -> "MY COMMUNITIES";
            case EXPLORE -> "EXPLORE COMMUNITIES";
            case DIRECT -> "ONLINE USERS";
        });
        searchField.setPromptText(switch (mode) {
            case COMMUNITY -> "Search my communities...";
            case EXPLORE -> "Search all communities...";
            case DIRECT -> "Search online users...";
        });
        boolean direct = mode == Mode.DIRECT;
        usersListView.setVisible(direct);
        usersListView.setManaged(direct);
        communitiesListView.setVisible(!direct);
        communitiesListView.setManaged(!direct);
        if (direct) adapter.requestUserList();
        else adapter.requestCommunityList();
        applyFilter(searchField.getText());
        if (direct && activePeer != null) selectPeerUser(activePeer);
        else if (!direct && activeCommunity != null && (mode == Mode.EXPLORE || activeCommunity.isJoined())) {
            selectCommunity(activeCommunity);
        } else showEmptyChatState();
    }

    private void updateRailButton(Button button, Mode mode, String icon) {
        boolean selected = currentMode == mode;
        button.getStyleClass().setAll(selected ? "rail-btn-active" : "rail-btn");
        button.setGraphic(IconUtils.createIconNode(icon, 18, selected ? "#09090B" : "#A1A1AA"));
    }

    @FXML
    private void handleShowDirectMessages() {
        handleSelectDirectTab();
    }

    private boolean isCreatingCommunity = false;

    @FXML
    private void handleOpenCreateCommunityDialog() {
        if (!adapter.isServerConnected() || adapter.isReconnecting()) return;
        if (isCreatingCommunity) return;
        isCreatingCommunity = true;
        try {
            Window window = titleBar != null && titleBar.getScene() != null ? titleBar.getScene().getWindow() : null;
            Optional<String> result = JavaFxUtils.showInputDialog(window, "Create Community", "Community Name:", "");
            result.ifPresent(name -> {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    adapter.createCommunity(trimmed);
                }
            });
        } finally {
            isCreatingCommunity = false;
        }
    }

    @FXML
    private void handleRefreshAll() {
        adapter.requestCommunityList();
        adapter.requestUserList();
    }

    private void selectCommunity(CommunityModel comm) {
        this.activeCommunity = comm;
        comm.clearUnread();
        communitiesListView.refresh();

        if (comm.isJoined() && adapter.isServerConnected() && !adapter.isReconnecting()) adapter.selectCommunity(comm.getId());

        targetNameLabel.setText(comm.getName());
        targetInitials.setText(comm.getInitials());
        targetAvatar.setStyle("-fx-background-color: " + JavaFxUtils.getAvatarColor(comm.getId()) + ";");
        targetStatusDot.setVisible(false);
        targetMetaLabel.setText("Community ID: " + comm.getId() + " • " + comm.getMemberCount() + " members");

        joinLeaveCommunityBtn.setVisible(true);
        joinLeaveCommunityBtn.setManaged(true);
        updateJoinLeaveButton(comm.isJoined());

        emptyStateView.setVisible(false);
        emptyStateView.setManaged(false);
        messageScrollPane.setVisible(true);
        messageScrollPane.setManaged(true);

        inputContainer.setDisable(!comm.isJoined() || !adapter.isServerConnected());
        messageInput.setPromptText(comm.isJoined() ? "Broadcast to #" + comm.getName() + "..." : "Join community to participate...");

        loadConversation(ConversationStore.communityKey(comm.getId()));
        Platform.runLater(() -> messageInput.requestFocus());
    }

    private void selectPeerUser(UserModel user) {
        this.activePeer = user;
        user.clearUnread();
        usersListView.refresh();

        boolean connectedPeer = adapter.isPeerConnected(user.getUsername());
        if (connectedPeer || adapter.isServerConnected()) adapter.selectPeer(user.getUsername());

        targetNameLabel.setText(user.getUsername());
        targetInitials.setText(user.getInitials());
        targetAvatar.setStyle("-fx-background-color: " + JavaFxUtils.getAvatarColor(user.getUsername()) + ";");
        targetStatusDot.setVisible(true);
        targetStatusDot.getStyleClass().clear();
        targetStatusDot.getStyleClass().add(user.isOnline() ? "status-dot-online" : "status-dot-offline");
        targetMetaLabel.setText(connectedPeer ? "Direct P2P Session Active"
                : adapter.isServerConnected() ? "Connecting..." : "Cần kết nối server để tìm peer mới");

        joinLeaveCommunityBtn.setVisible(false);
        joinLeaveCommunityBtn.setManaged(false);

        emptyStateView.setVisible(false);
        emptyStateView.setManaged(false);
        messageScrollPane.setVisible(true);
        messageScrollPane.setManaged(true);

        inputContainer.setDisable(!connectedPeer);
        messageInput.setPromptText("Message @" + user.getUsername() + "...");

        loadConversation(ConversationStore.directKey(user.getUsername()));
        Platform.runLater(() -> messageInput.requestFocus());
    }

    private void updateJoinLeaveButton(boolean isJoined) {
        joinLeaveCommunityBtn.setDisable(!adapter.isServerConnected());
        if (isJoined) {
            joinLeaveCommunityBtn.setText("Leave");
            joinLeaveCommunityBtn.getStyleClass().clear();
            joinLeaveCommunityBtn.getStyleClass().add("btn-secondary");
        } else {
            joinLeaveCommunityBtn.setText("Join Community");
            joinLeaveCommunityBtn.getStyleClass().clear();
            joinLeaveCommunityBtn.getStyleClass().add("btn-primary");
        }
    }

    @FXML
    private void handleToggleJoinCommunity() {
        if (!adapter.isServerConnected() || adapter.isReconnecting()) return;
        if (activeCommunity == null) return;
        if (activeCommunity.isJoined()) {
            Window owner = titleBar.getScene().getWindow();
            if (!JavaFxUtils.showConfirmDialog(owner, "Leave Community",
                    "Are you sure you want to leave #" + activeCommunity.getName() + "?")) return;
            adapter.leaveCommunity(activeCommunity.getId());
        } else {
            adapter.joinCommunity(activeCommunity.getId());
        }
        joinLeaveCommunityBtn.setDisable(true);
    }

    private void loadConversation(String convKey) {
        messagesContainer.getChildren().clear();
        ObservableList<ChatMessageModel> history = conversations.history(convKey);
        for (ChatMessageModel msg : history) {
            messageView.render(msg);
        }
        scrollToBottom();
    }

    @FXML
    private void handleSendMessage() {
        String text = messageInput.getText().trim();
        if (text.isEmpty()) return;
        if ((currentMode == Mode.DIRECT && activePeer == null)
                || (currentMode != Mode.DIRECT && activeCommunity == null)) return;
        messageInput.clear();

        boolean direct = currentMode == Mode.DIRECT;
        String target = direct ? activePeer.getUsername() : activeCommunity.getId();
        ChatMessageModel msg = new ChatMessageModel(adapter.getCurrentUsername(), text, true);
        msg.sending();
        String convKey = (direct ? "peer-" : "comm-") + target;
        conversations.add(convKey, msg);
        messageView.render(msg);
        if (direct) adapter.sendChatMessage(target, text, msg::sent);
        else adapter.broadcastMessage(target, text, msg::sent);
        scrollToBottom();
    }

    @FXML
    private void handleAttachFile() {
        FileChooser chooser = new FileChooser();
        String title = (currentMode != Mode.DIRECT)
                ? "Select File to Broadcast to #" + (activeCommunity != null ? activeCommunity.getName() : "Community")
                : "Select File to Send to @" + (activePeer != null ? activePeer.getUsername() : "User");
        chooser.setTitle(title);

        Stage stage = (Stage) attachBtn.getScene().getWindow();
        File file = chooser.showOpenDialog(stage);

        if (file != null) {
            if (file.length() > protocols.FileTransferMeta.MAX_FILE_SIZE) {
                JavaFxUtils.showErrorAlert("File Too Large", "Maximum supported file size is 500 MiB.");
                return;
            }

            if (currentMode != Mode.DIRECT && activeCommunity != null) {
                adapter.broadcastFile(activeCommunity.getId(), activeCommunity.getName(), file);
            } else if (currentMode == Mode.DIRECT && activePeer != null) {
                adapter.sendFile(activePeer.getUsername(), file);
            }
        }
    }

    private void scrollToBottom() {
        Platform.runLater(() -> messageScrollPane.setVvalue(1.0));
    }

    @FXML
    private void handleMinimize() {
        ((Stage) titleBar.getScene().getWindow()).setIconified(true);
    }

    @FXML
    private void handleMaximize() {
        Stage stage = (Stage) titleBar.getScene().getWindow();
        WindowDragHelper.toggleMaximize(stage);
    }

    @FXML
    private void handleClose() {
        ChatClientAdapter.getInstance().logout();
        Platform.exit();
        System.exit(0);
    }

    @FXML
    private void handleLogout() {
        Stage stage = (Stage) logoutBtn.getScene().getWindow();
        boolean confirmed = JavaFxUtils.showConfirmDialog(stage, "Log Out", "Are you sure you want to log out?");
        if (confirmed) {
            adapter.removeListener(this);
            adapter.logout();
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/login.fxml"));
                Parent root = loader.load();
                stage.setScene(new Scene(root, 800, 560));
                stage.setTitle("DL-Chat");
                stage.setMinWidth(800);
                stage.setMinHeight(560);
                stage.centerOnScreen();
            } catch (Exception e) {
                e.printStackTrace();
                JavaFxUtils.showErrorAlert(stage, "Navigation Error", "Failed to return to login: " + e.getMessage());
            }
        }
    }

    @Override
    public void onCommunityListUpdated(List<CommunityModel> communities) {
        if (!allCommunities.equals(communities)) allCommunities.setAll(communities);
        communitiesListView.refresh();
        if (activeCommunity != null) {
            boolean found = false;
            for (CommunityModel c : allCommunities) {
                if (c.getId().equals(activeCommunity.getId())) {
                    found = true;
                    activeCommunity = c;
                    updateJoinLeaveButton(c.isJoined());
                    break;
                }
            }
            if (!found) {
                activeCommunity = null;
                if (currentMode != Mode.DIRECT) showEmptyChatState();
            }
        }
        updateConnectionState();
        applyFilter(searchField.getText());
    }

    @Override
    public void onCommunityJoined(String communityId, String communityName) {
        for (CommunityModel c : allCommunities) {
            if (c.getId().equals(communityId)) {
                c.setJoined(true);
                break;
            }
        }
        if (currentMode != Mode.DIRECT && activeCommunity != null && activeCommunity.getId().equals(communityId)) {
            activeCommunity.setJoined(true);
            updateJoinLeaveButton(true);
            inputContainer.setDisable(!adapter.isServerConnected());
        }
        applyFilter(searchField.getText());
    }

    @Override
    public void onCommunityLeft(String communityId) {
        for (CommunityModel c : allCommunities) {
            if (c.getId().equals(communityId)) {
                c.setJoined(false);
                break;
            }
        }
        if (activeCommunity != null && activeCommunity.getId().equals(communityId)) {
            activeCommunity.setJoined(false);
            updateJoinLeaveButton(false);
            inputContainer.setDisable(true);
            String q = searchField.getText();
            if (q == null || q.trim().isEmpty()) {
                showEmptyChatState();
            }
        }
        applyFilter(searchField.getText());
    }

    @Override
    public void onBroadcastMessageReceived(String communityId, String sender, String message) {
        if (communityId == null || communityId.isBlank()) {
            onSystemNotification("Broadcast missing community ID", true);
            return;
        }
        appendMessage(ConversationStore.communityKey(communityId),
                new ChatMessageModel(sender, message, sender.equals(adapter.getCurrentUsername())));
    }

    @Override
    public void onUserListUpdated(List<String> onlineUsers) {
        String current = adapter.getCurrentUsername();
        List<UserModel> updated = new ArrayList<>();
        for (String u : onlineUsers) {
            if (u.equalsIgnoreCase(current)) continue;
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
        for (UserModel existing : allUsers) {
            if (adapter.isPeerConnected(existing.getUsername())
                    && updated.stream().noneMatch(user -> user.getUsername().equals(existing.getUsername()))) {
                existing.setOnline(true);
                updated.add(existing);
            }
        }
        allUsers.setAll(updated);
        if (currentMode == Mode.DIRECT && activePeer != null) {
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
        if (currentMode == Mode.DIRECT && activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            targetMetaLabel.setText("Direct P2P Session Active");
            targetStatusDot.setVisible(true);
            targetStatusDot.getStyleClass().clear();
            targetStatusDot.getStyleClass().add("status-dot-online");
            inputContainer.setDisable(false);
        }
    }

    @Override
    public void onPeerConnectionFailed(String peerUsername, String reason) {
        if (currentMode == Mode.DIRECT && activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            targetMetaLabel.setText("Connection failed: " + reason);
        }
    }

    @Override
    public void onMessageReceived(String peerUsername, String message) {
        appendMessage(ConversationStore.directKey(peerUsername), new ChatMessageModel(peerUsername, message, false));
    }

    @Override
    public void onFileOfferReceived(FileOfferModel offer) {
        try {
            ConversationStore.Entry entry = ConversationStore.fileOffer(offer);
            appendMessage(entry.key(), entry.message());
        } catch (IllegalArgumentException e) { onSystemNotification(e.getMessage(), true); }
    }

    private void appendMessage(String key, ChatMessageModel message) {
        conversations.add(key, message);
        String activeKey = currentMode == Mode.DIRECT
                ? (activePeer == null ? null : ConversationStore.directKey(activePeer.getUsername()))
                : (activeCommunity == null ? null : ConversationStore.communityKey(activeCommunity.getId()));
        if (key.equals(activeKey)) {
            messageView.render(message);
            scrollToBottom();
        } else if (!message.isMine()) {
            allUsers.stream().filter(user -> ConversationStore.directKey(user.getUsername()).equals(key))
                    .findFirst().ifPresent(UserModel::incrementUnread);
            allCommunities.stream().filter(group -> ConversationStore.communityKey(group.getId()).equals(key))
                    .findFirst().ifPresent(CommunityModel::incrementUnread);
            usersListView.refresh();
            communitiesListView.refresh();
        }
    }

    @Override
    public void onPeerDisconnected(String peerUsername) {
        if (currentMode == Mode.DIRECT && activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            targetMetaLabel.setText("Peer disconnected");
            inputContainer.setDisable(true);
        }
    }

    @Override
    public void onConnectionClosed() {
        updateConnectionState();
        if (currentMode == Mode.DIRECT && activePeer != null && adapter.isPeerConnected(activePeer.getUsername())) {
            targetMetaLabel.setText("P2P vẫn kết nối • server không khả dụng");
        }
    }

    @FXML
    private void handleReconnect() {
        if (adapter.isServerConnected() || adapter.isReconnecting()) return;
        JavaFxUtils.showPasswordDialog(titleBar.getScene().getWindow(), "Kết nối lại",
                "Mật khẩu của " + adapter.getCurrentUsername()).ifPresent(adapter::reconnect);
    }

    private void updateConnectionState() {
        boolean ready = adapter.isServerConnected() && !adapter.isReconnecting();
        connectionView.render(adapter.connectionState());
        addCommunityBtn.setDisable(!ready);
        joinLeaveCommunityBtn.setDisable(!ready);
        if (currentMode == Mode.DIRECT) {
            inputContainer.setDisable(activePeer == null || !adapter.isPeerConnected(activePeer.getUsername()));
        } else inputContainer.setDisable(!ready || activeCommunity == null || !activeCommunity.isJoined());
    }

    @Override public void onReconnecting() { updateConnectionState(); }
    @Override public void onReconnected() {
        updateConnectionState();
        if (currentMode == Mode.DIRECT && activePeer != null && adapter.isPeerConnected(activePeer.getUsername())) {
            targetMetaLabel.setText("Direct P2P Session Active");
        }
    }
    @Override public void onReconnectFailed(String reason) {
        updateConnectionState();
        connectionView.showReconnectError(reason);
    }

    @Override public void onSystemNotification(String message, boolean isError) {
        if (isError) {
            joinLeaveCommunityBtn.setDisable(!adapter.isServerConnected());
            JavaFxUtils.showErrorAlert("Connection Error", message);
        }
    }
}
