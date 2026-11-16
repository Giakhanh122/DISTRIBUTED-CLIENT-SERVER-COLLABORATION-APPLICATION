package ui.controller;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javafx.animation.PauseTransition;
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
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
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
    @FXML private Button railGlobalBtn;
    @FXML private Button addCommunityBtn;

    @FXML private TextField searchField;
    @FXML private Label sidebarSectionTitle;
    @FXML private ListView<CommunityModel> communitiesListView;
    @FXML private ListView<UserModel> usersListView;
    @FXML private Label globalAudienceView;
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
    @FXML private VBox attachmentTray;
    @FXML private FlowPane attachmentsFlow;
    @FXML private Label attachmentSummary;
    @FXML private Button clearAttachmentsBtn;
    @FXML private TextField messageInput;
    @FXML private Button sendBtn;

    private final ChatClientAdapter adapter = ChatClientAdapter.getInstance();
    private final List<File> pendingAttachments = new ArrayList<>();
    private String pendingTargetKey;
    private String pendingTargetLabel;

    private static final List<String> IMAGE_EXTENSIONS = List.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp");

    private final ObservableList<CommunityModel> allCommunities = FXCollections.observableArrayList();
    private FilteredList<CommunityModel> filteredCommunities;

    private final ObservableList<UserModel> allUsers = FXCollections.observableArrayList();
    private FilteredList<UserModel> filteredUsers;

    private final ConversationStore conversations = new ConversationStore();

    private enum Mode { COMMUNITY, EXPLORE, DIRECT, GLOBAL }
    private Mode currentMode = Mode.COMMUNITY;

    private ChatMessageView messageView;
    private ConnectionStatusView connectionView;

    private CommunityModel activeCommunity = null;
    private UserModel activePeer = null;
    private final Map<String, PendingReceipt> pendingReceipts = new HashMap<>();
    private final PauseTransition localTypingTimeout = new PauseTransition(Duration.seconds(2));
    private final PauseTransition remoteTypingTimeout = new PauseTransition(Duration.seconds(5));
    private boolean localTyping;
    private String localTypingPeer;
    private String remoteTypingPeer;

    private record PendingReceipt(String peer, ChatMessageModel message, PauseTransition timeout) {}

    @FXML
    public void initialize() {
        connectionView = new ConnectionStatusView(connectionStatusLabel, connectionStatusDot, reconnectBtn);
        messageView = new ChatMessageView(messagesContainer, adapter::acceptFileOffer, adapter::rejectFileOffer);
        localTypingTimeout.setOnFinished(event -> stopLocalTyping());
        remoteTypingTimeout.setOnFinished(event -> {
            String peer = remoteTypingPeer;
            clearRemoteTyping();
            if (peer != null && currentMode == Mode.DIRECT && activePeer != null
                    && activePeer.getUsername().equalsIgnoreCase(peer)) {
                targetMetaLabel.setText(adapter.isPeerConnected(peer)
                        ? "Direct P2P Session Active" : "Peer disconnected");
            }
        });
        messageInput.textProperty().addListener((observable, oldText, newText) -> updateLocalTyping(newText));

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
        railGlobalBtn.setTooltip(new Tooltip("Global Chat"));
        addCommunityBtn.setTooltip(new Tooltip("Create Community"));

        logoutBtn.setGraphic(IconUtils.createIconNode(IconUtils.LOGOUT, 14, "#71717A"));
        logoutBtn.setTooltip(new Tooltip("Log Out"));

        emptyStateIconContainer.getChildren().add(IconUtils.createIconNode(IconUtils.CHAT_BUBBLE, 44, "#D4D4D8"));

        attachBtn.setGraphic(IconUtils.createIconNode(IconUtils.PAPERCLIP, 16, "#71717A"));
        attachBtn.setTooltip(new Tooltip("Attach files and images"));

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
            if (newV != null && (currentMode == Mode.COMMUNITY || currentMode == Mode.EXPLORE)) {
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
        stopLocalTyping();
        clearRemoteTyping();
        if (currentMode == Mode.DIRECT) activePeer = null;
        else if (currentMode != Mode.GLOBAL) activeCommunity = null;
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
        attachBtn.setDisable(false);
        messageInput.clear();
        messageInput.setPromptText("Select a community or user...");
    }

    @FXML
    private void handleSelectCommunitiesTab() { selectNavigationTab(Mode.COMMUNITY); }

    @FXML
    private void handleSelectExploreTab() { selectNavigationTab(Mode.EXPLORE); }

    @FXML
    private void handleSelectDirectTab() { selectNavigationTab(Mode.DIRECT); }

    @FXML
    private void handleSelectGlobalTab() { selectNavigationTab(Mode.GLOBAL); }

    private void selectNavigationTab(Mode mode) {
        stopLocalTyping();
        clearRemoteTyping();
        currentMode = mode;
        updateRailButton(railCommunitiesBtn, Mode.COMMUNITY, IconUtils.CHAT_BUBBLE);
        updateRailButton(railExploreBtn, Mode.EXPLORE, IconUtils.COMPASS);
        updateRailButton(railDirectBtn, Mode.DIRECT, IconUtils.USER);
        updateRailButton(railGlobalBtn, Mode.GLOBAL, IconUtils.USERS);
        sidebarSectionTitle.setText(switch (mode) {
            case COMMUNITY -> "MY COMMUNITIES";
            case EXPLORE -> "EXPLORE COMMUNITIES";
            case DIRECT -> "ONLINE USERS";
            case GLOBAL -> "GLOBAL CHAT";
        });
        searchField.setVisible(mode != Mode.GLOBAL);
        searchField.setManaged(mode != Mode.GLOBAL);
        searchField.setPromptText(switch (mode) {
            case COMMUNITY -> "Search my communities...";
            case EXPLORE -> "Search all communities...";
            case DIRECT -> "Search online users...";
            case GLOBAL -> "Global chat";
        });
        boolean direct = mode == Mode.DIRECT;
        boolean global = mode == Mode.GLOBAL;
        usersListView.setVisible(direct);
        usersListView.setManaged(direct);
        communitiesListView.setVisible(!direct && !global);
        communitiesListView.setManaged(!direct && !global);
        globalAudienceView.setVisible(global);
        globalAudienceView.setManaged(global);
        attachBtn.setDisable(global);
        if (direct) adapter.requestUserList();
        else if (!global) adapter.requestCommunityList();
        applyFilter(searchField.getText());
        if (global) showGlobalConversation();
        else if (direct && activePeer != null) selectPeerUser(activePeer);
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
        stopLocalTyping();
        clearRemoteTyping();
        this.activeCommunity = comm;
        attachBtn.setDisable(false);
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
        stopLocalTyping();
        clearRemoteTyping();
        this.activePeer = user;
        attachBtn.setDisable(false);
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

    private void showGlobalConversation() {
        stopLocalTyping();
        clearRemoteTyping();
        targetNameLabel.setText("Global Chat");
        targetInitials.setText("G");
        targetAvatar.setStyle("-fx-background-color: #F59E0B;");
        targetStatusDot.setVisible(false);
        targetMetaLabel.setText("Broadcast to all online users");
        joinLeaveCommunityBtn.setVisible(false);
        joinLeaveCommunityBtn.setManaged(false);
        emptyStateView.setVisible(false);
        emptyStateView.setManaged(false);
        messageScrollPane.setVisible(true);
        messageScrollPane.setManaged(true);
        inputContainer.setDisable(!adapter.isServerConnected() || adapter.isReconnecting());
        attachBtn.setDisable(true);
        messageInput.setPromptText("Message everyone online...");
        loadConversation(ConversationStore.globalKey());
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
        boolean direct = currentMode == Mode.DIRECT;
        boolean global = currentMode == Mode.GLOBAL;
        if ((direct && activePeer == null)
                || (!direct && !global && activeCommunity == null)) return;

        String target = direct ? activePeer.getUsername()
                : global ? null : activeCommunity.getId();
        String convKey = direct ? ConversationStore.directKey(target)
                : global ? ConversationStore.globalKey() : ConversationStore.communityKey(target);
        if (!pendingAttachments.isEmpty() && !convKey.equals(pendingTargetKey)) {
            JavaFxUtils.showErrorAlert("Attachments not sent",
                    "The selected files are staged for " + pendingTargetLabel
                            + ". Return to that conversation or clear the attachments first.");
            return;
        }
        if (global && !pendingAttachments.isEmpty()) {
            JavaFxUtils.showErrorAlert("Attachments not sent",
                    "Global chat supports text messages only. Clear the staged files or return to their conversation.");
            return;
        }
        if (text.isEmpty() && pendingAttachments.isEmpty()) return;

        List<File> attachments = List.copyOf(pendingAttachments);
        String communityName = direct || global ? null : activeCommunity.getName();
        stopLocalTyping();
        messageInput.clear();
        clearPendingAttachments();

        if (!text.isEmpty()) {
            ChatMessageModel msg = new ChatMessageModel(adapter.getCurrentUsername(), text, true);
            msg.sending();
            conversations.add(convKey, msg);
            messageView.render(msg);
            if (direct) {
                String messageId = UUID.randomUUID().toString();
                trackPendingReceipt(messageId, target, msg);
                adapter.sendChatMessage(target, text, messageId, error -> {
                    msg.sent(error);
                    if (error != null) clearPendingReceipt(messageId);
                });
            } else if (global) adapter.broadcastGlobalMessage(text, msg::sent);
            else adapter.broadcastMessage(target, text, msg::sent);
        }

        for (File file : attachments) {
            if (direct) adapter.sendFile(target, file);
            else if (!global) adapter.broadcastFile(target, communityName, file);
        }
        scrollToBottom();
    }

    private void updateLocalTyping(String text) {
        if (currentMode != Mode.DIRECT || activePeer == null || !adapter.isPeerConnected(activePeer.getUsername())) {
            stopLocalTyping();
            return;
        }
        if (text == null || text.isEmpty()) {
            stopLocalTyping();
            return;
        }
        String peer = activePeer.getUsername();
        if (!localTyping || !peer.equals(localTypingPeer)) {
            stopLocalTyping();
            localTyping = true;
            localTypingPeer = peer;
            adapter.sendTyping(peer, true);
        }
        localTypingTimeout.playFromStart();
    }

    private void stopLocalTyping() {
        localTypingTimeout.stop();
        String peer = localTypingPeer;
        localTypingPeer = null;
        boolean wasTyping = localTyping;
        localTyping = false;
        if (wasTyping && peer != null && adapter.isPeerConnected(peer)) adapter.sendTyping(peer, false);
    }

    private void clearRemoteTyping() {
        remoteTypingTimeout.stop();
        remoteTypingPeer = null;
    }

    private void trackPendingReceipt(String messageId, String peer, ChatMessageModel message) {
        PauseTransition timeout = new PauseTransition(Duration.seconds(30));
        timeout.setOnFinished(event -> pendingReceipts.remove(messageId));
        pendingReceipts.put(messageId, new PendingReceipt(peer, message, timeout));
        timeout.playFromStart();
    }

    private void clearPendingReceipt(String messageId) {
        PendingReceipt pending = pendingReceipts.remove(messageId);
        if (pending != null) pending.timeout().stop();
    }

    private void clearPendingReceipts(String peer) {
        for (var entry : new ArrayList<>(pendingReceipts.entrySet())) {
            if (entry.getValue().peer().equalsIgnoreCase(peer)) clearPendingReceipt(entry.getKey());
        }
    }

    @FXML
    private void handleAttachFile() {
        String targetKey = currentConversationKey();
        if (targetKey == null) return;
        if (!pendingAttachments.isEmpty() && !targetKey.equals(pendingTargetKey)) {
            JavaFxUtils.showErrorAlert("Attachments not added",
                    "The current selection belongs to " + pendingTargetLabel
                            + ". Clear it before attaching files to another conversation.");
            return;
        }

        FileChooser chooser = new FileChooser();
        String title = (currentMode != Mode.DIRECT)
                ? "Select Files to Broadcast to #" + (activeCommunity != null ? activeCommunity.getName() : "Community")
                : "Select Files to Send to @" + (activePeer != null ? activePeer.getUsername() : "User");
        chooser.setTitle(title);
        FileChooser.ExtensionFilter images = new FileChooser.ExtensionFilter(
                "Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp");
        FileChooser.ExtensionFilter allFiles = new FileChooser.ExtensionFilter("All files", "*.*");
        chooser.getExtensionFilters().addAll(images, allFiles);
        chooser.setSelectedExtensionFilter(allFiles);

        Stage stage = (Stage) attachBtn.getScene().getWindow();
        List<File> selected = chooser.showOpenMultipleDialog(stage);
        if (selected == null || selected.isEmpty()) return;

        if (pendingAttachments.isEmpty()) {
            pendingTargetKey = targetKey;
            pendingTargetLabel = currentConversationLabel();
        }

        List<String> skipped = new ArrayList<>();
        for (File file : selected) {
            if (!file.isFile() || !file.canRead()) {
                skipped.add(file.getName() + ": not a readable file");
                continue;
            }
            if (file.length() > protocols.FileTransferMeta.MAX_FILE_SIZE) {
                skipped.add(file.getName() + ": exceeds the 500 MiB limit");
                continue;
            }
            if (containsAttachment(file)) continue;
            pendingAttachments.add(file);
        }
        renderPendingAttachments();
        if (!skipped.isEmpty()) {
            JavaFxUtils.showErrorAlert("Some files were skipped", String.join("\n", skipped));
        }
    }

    @FXML
    private void handleClearAttachments() {
        clearPendingAttachments();
    }

    private String currentConversationKey() {
        if (currentMode == Mode.DIRECT) {
            return activePeer == null ? null : ConversationStore.directKey(activePeer.getUsername());
        }
        if (currentMode == Mode.GLOBAL) return ConversationStore.globalKey();
        return activeCommunity == null ? null : ConversationStore.communityKey(activeCommunity.getId());
    }

    private String currentConversationLabel() {
        if (currentMode == Mode.DIRECT) return "@" + activePeer.getUsername();
        if (currentMode == Mode.GLOBAL) return "Global Chat";
        return "#" + activeCommunity.getName();
    }

    private boolean containsAttachment(File candidate) {
        return pendingAttachments.stream().anyMatch(file ->
                file.toPath().toAbsolutePath().normalize()
                        .equals(candidate.toPath().toAbsolutePath().normalize()));
    }

    private void renderPendingAttachments() {
        attachmentsFlow.getChildren().clear();
        if (pendingAttachments.isEmpty()) {
            attachmentTray.setVisible(false);
            attachmentTray.setManaged(false);
            pendingTargetKey = null;
            pendingTargetLabel = null;
            return;
        }

        long totalSize = pendingAttachments.stream().mapToLong(File::length).sum();
        attachmentSummary.setText(pendingAttachments.size() + " file(s) • "
                + FileOfferModel.formatFileSize(totalSize) + " • for " + pendingTargetLabel);
        attachmentTray.setVisible(true);
        attachmentTray.setManaged(true);

        for (File file : pendingAttachments) {
            StackPane preview = createAttachmentPreview(file);
            VBox details = new VBox(2);
            Label name = new Label(file.getName());
            name.getStyleClass().add("attachment-name");
            name.setMaxWidth(145);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            Label size = new Label(FileOfferModel.formatFileSize(file.length()));
            size.getStyleClass().add("attachment-size");
            details.getChildren().addAll(name, size);

            Button remove = new Button("×");
            remove.getStyleClass().add("attachment-remove");
            remove.setTooltip(new Tooltip("Remove " + file.getName()));
            remove.setOnAction(event -> {
                pendingAttachments.remove(file);
                renderPendingAttachments();
            });

            HBox chip = new HBox(8, preview, details, remove);
            chip.getStyleClass().add("attachment-chip");
            chip.setAlignment(Pos.CENTER_LEFT);
            attachmentsFlow.getChildren().add(chip);
        }
    }

    private StackPane createAttachmentPreview(File file) {
        StackPane preview = new StackPane();
        preview.getStyleClass().add("attachment-preview");
        preview.getChildren().add(IconUtils.createIconNode(IconUtils.FILE, 22, "#F59E0B"));
        String lowerName = file.getName().toLowerCase(Locale.ROOT);
        if (IMAGE_EXTENSIONS.stream().anyMatch(lowerName::endsWith)) {
            Image image = new Image(file.toURI().toString(), 42, 42, true, true, true);
            ImageView thumbnail = new ImageView(image);
            thumbnail.setFitWidth(42);
            thumbnail.setFitHeight(42);
            thumbnail.setPreserveRatio(true);
            preview.getChildren().add(thumbnail);
        }
        return preview;
    }

    private void clearPendingAttachments() {
        pendingAttachments.clear();
        renderPendingAttachments();
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
                if (currentMode == Mode.COMMUNITY || currentMode == Mode.EXPLORE) showEmptyChatState();
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
        if ((currentMode == Mode.COMMUNITY || currentMode == Mode.EXPLORE)
                && activeCommunity != null && activeCommunity.getId().equals(communityId)) {
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
            if (currentMode == Mode.COMMUNITY || currentMode == Mode.EXPLORE) {
                updateJoinLeaveButton(false);
                inputContainer.setDisable(true);
                String q = searchField.getText();
                if (q == null || q.trim().isEmpty()) {
                    showEmptyChatState();
                }
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
    public void onGlobalBroadcastMessageReceived(String sender, String message) {
        appendMessage(ConversationStore.globalKey(),
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
            UserModel latestPeer = allUsers.stream()
                    .filter(user -> user.getUsername().equalsIgnoreCase(activePeer.getUsername()))
                    .findFirst().orElse(null);
            if (latestPeer != null) {
                activePeer = latestPeer;
                usersListView.getSelectionModel().select(latestPeer);
            } else {
                activePeer.setOnline(false);
            }
            boolean online = activePeer.isOnline() || adapter.isPeerConnected(activePeer.getUsername());
            targetStatusDot.getStyleClass().setAll(online ? "status-dot-online" : "status-dot-offline");
            if (!online) {
                targetMetaLabel.setText("Peer offline");
            }
        }
    }

    @Override
    public void onPeerConnected(String peerUsername) {
        if (currentMode == Mode.DIRECT && activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            clearRemoteTyping();
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
    public void onMessageDelivered(String peerUsername, String messageId) {
        PendingReceipt pending = pendingReceipts.get(messageId);
        if (pending == null || !pending.peer().equalsIgnoreCase(peerUsername)) return;
        clearPendingReceipt(messageId);
        pending.message().delivered();
    }

    @Override
    public void onTypingIndicator(String peerUsername, boolean typing) {
        if (currentMode != Mode.DIRECT || activePeer == null
                || !activePeer.getUsername().equalsIgnoreCase(peerUsername)) return;
        if (!typing) {
            if (peerUsername.equalsIgnoreCase(remoteTypingPeer)) {
                clearRemoteTyping();
                targetMetaLabel.setText(adapter.isPeerConnected(peerUsername)
                        ? "Direct P2P Session Active" : "Peer disconnected");
            }
            return;
        }
        remoteTypingPeer = peerUsername;
        targetMetaLabel.setText(peerUsername + " is typing...");
        remoteTypingTimeout.playFromStart();
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
                : currentMode == Mode.GLOBAL ? ConversationStore.globalKey()
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
        clearPendingReceipts(peerUsername);
        if (peerUsername.equalsIgnoreCase(localTypingPeer)) stopLocalTyping();
        if (peerUsername.equalsIgnoreCase(remoteTypingPeer)) clearRemoteTyping();
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
        } else if (currentMode == Mode.GLOBAL) {
            inputContainer.setDisable(!ready);
            attachBtn.setDisable(true);
        } else {
            inputContainer.setDisable(!ready || activeCommunity == null || !activeCommunity.isJoined());
            attachBtn.setDisable(false);
        }
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
