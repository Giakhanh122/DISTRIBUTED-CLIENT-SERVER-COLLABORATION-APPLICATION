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
    @FXML private Label directUnreadBadge;
    @FXML private Label communityUnreadBadge;
    @FXML private Label globalUnreadBadge;
    @FXML private Button railGlobalBtn;
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
    @FXML private HBox serverOfflineBanner;
    @FXML private Label serverOfflineMessage;
    @FXML private Button bannerReconnectBtn;
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

    @FXML private HBox currentUserFooter;
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
    private VBox joinedCommunitiesPlaceholder;
    private final Label communityFilterPlaceholder = new Label();
    private boolean syncingCommunityList;

    private final ObservableList<UserModel> allUsers = FXCollections.observableArrayList();
    private FilteredList<UserModel> filteredUsers;

    private final ConversationStore conversations = new ConversationStore();
    private final Map<String, Integer> unreadCounts = new HashMap<>();

    private enum Mode { COMMUNITY, EXPLORE, DIRECT, GLOBAL }
    private Mode currentMode = Mode.COMMUNITY;

    private ChatMessageView messageView;
    private ConnectionStatusView connectionView;

    private CommunityModel activeCommunity = null;
    private UserModel activePeer = null;
    private boolean syncingUserList;
    private final Map<String, PendingReceipt> pendingReceipts = new HashMap<>();

    private record PendingReceipt(String peer, ChatMessageModel message, PauseTransition timeout) {}

    @FXML
    public void initialize() {
        // Keep both footer borders aligned, including attachments and reconnect controls.
        inputContainer.minHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> Math.max(64, currentUserFooter.minHeight(currentUserFooter.getWidth())),
                currentUserFooter.widthProperty(), reconnectBtn.managedProperty(), connectionStatusLabel.textProperty()));
        currentUserFooter.prefHeightProperty().bind(inputContainer.heightProperty());
        targetMetaLabel.visibleProperty().bind(targetMetaLabel.textProperty().isNotEmpty());
        targetMetaLabel.managedProperty().bind(targetMetaLabel.visibleProperty());
        connectionView = new ConnectionStatusView(connectionStatusLabel, connectionStatusDot, reconnectBtn,
                serverOfflineBanner, serverOfflineMessage, bannerReconnectBtn);
        messageView = new ChatMessageView(messagesContainer, adapter::acceptFileOffer, adapter::rejectFileOffer, adapter::cancelFileTransfer);

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
        joinedCommunitiesPlaceholder = emptyCommBox;
        communityFilterPlaceholder.setStyle("-fx-text-fill: #71717A; -fx-font-size: 12px;");

        filteredCommunities = new FilteredList<>(allCommunities, CommunityModel::isJoined);
        communitiesListView.setItems(filteredCommunities);

        communitiesListView.setCellFactory(list -> ConversationCells.community());

        communitiesListView.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && (currentMode == Mode.COMMUNITY || currentMode == Mode.EXPLORE)
                    && !syncingCommunityList) {
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
            if (newV != null && currentMode == Mode.DIRECT && !syncingUserList) {
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
        String q = (query == null) ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (currentMode != Mode.DIRECT) {
            boolean joined = currentMode == Mode.COMMUNITY;
            syncingCommunityList = true;
            try {
                filteredCommunities.setPredicate(c -> c.isJoined() == joined
                        && c.getName().toLowerCase(Locale.ROOT).contains(q));
            } finally {
                syncingCommunityList = false;
            }
            communityFilterPlaceholder.setText(q.isEmpty()
                    ? "No new communities to explore" : "No matching communities");
            communitiesListView.setPlaceholder(joined && q.isEmpty()
                    ? joinedCommunitiesPlaceholder : communityFilterPlaceholder);
        }
        filteredUsers.setPredicate(u -> q.isEmpty() || u.getUsername().toLowerCase(Locale.ROOT).contains(q));
    }

    private void showEmptyChatState() {
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
        messageInput.setPromptText("Select a community or user");
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
            case COMMUNITY -> "Search my communities";
            case EXPLORE -> "Search communities to join";
            case DIRECT -> "Search online users";
            case GLOBAL -> "Global chat";
        });
        boolean direct = mode == Mode.DIRECT;
        boolean global = mode == Mode.GLOBAL;
        usersListView.setVisible(direct);
        usersListView.setManaged(direct);
        communitiesListView.setVisible(!direct && !global);
        communitiesListView.setManaged(!direct && !global);
        attachBtn.setDisable(global);
        if (direct) adapter.requestUserList();
        else if (!global) adapter.requestCommunityList();
        applyFilter(searchField.getText());
        if (global) showGlobalConversation();
        else if (direct && activePeer != null) selectPeerUser(activePeer);
        else if (!direct && activeCommunity != null
                && activeCommunity.isJoined() == (mode == Mode.COMMUNITY)) {
            selectCommunity(activeCommunity);
        } else showEmptyChatState();
        updateConnectionState();
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
        showCreateCommunityDialog("");
    }

    private void showCreateCommunityDialog(String initialName) {
        if (!adapter.isServerConnected() || adapter.isReconnecting()) return;
        if (isCreatingCommunity) return;
        isCreatingCommunity = true;
        try {
            Window window = titleBar != null && titleBar.getScene() != null ? titleBar.getScene().getWindow() : null;
            Optional<String> result = JavaFxUtils.showInputDialog(window, "Create Community", "Community Name:", initialName);
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

    @Override
    public void onCommunityCreateFailed(String name, String reason) {
        Window owner = titleBar != null && titleBar.getScene() != null ? titleBar.getScene().getWindow() : null;
        JavaFxUtils.showErrorAlert(owner, "Unable to create community", reason);
        showCreateCommunityDialog(name);
    }

    @FXML
    private void handleRefreshAll() {
        adapter.requestCommunityList();
        adapter.requestUserList();
    }

    private void selectCommunity(CommunityModel comm) {
        this.activeCommunity = comm;
        unreadCounts.remove(ConversationStore.communityKey(comm.getId()));
        attachBtn.setDisable(false);
        comm.clearUnread();
        updateUnreadBadges();
        communitiesListView.refresh();

        if (comm.isJoined() && adapter.isServerConnected() && !adapter.isReconnecting()) adapter.selectCommunity(comm.getId());

        targetNameLabel.setText(comm.getName());
        targetInitials.setText(comm.getInitials());
        targetAvatar.setStyle("-fx-background-color: " + JavaFxUtils.getAvatarColor(comm.getId()) + ";");
        targetStatusDot.setVisible(false);
        targetMetaLabel.setText(comm.getMemberCount() + " members");

        joinLeaveCommunityBtn.setVisible(true);
        joinLeaveCommunityBtn.setManaged(true);
        updateJoinLeaveButton(comm.isJoined());

        emptyStateView.setVisible(false);
        emptyStateView.setManaged(false);
        messageScrollPane.setVisible(true);
        messageScrollPane.setManaged(true);

        inputContainer.setDisable(!comm.isJoined() || !adapter.isServerConnected());
        messageInput.setPromptText(comm.isJoined() ? "Type to chat" : "Join community to participate");

        loadConversation(ConversationStore.communityKey(comm.getId()));
        Platform.runLater(() -> messageInput.requestFocus());
    }

    private void selectPeerUser(UserModel user) {
        this.activePeer = user;
        unreadCounts.remove(ConversationStore.directKey(user.getUsername()));
        attachBtn.setDisable(false);
        user.clearUnread();
        updateUnreadBadges();
        usersListView.refresh();

        boolean connectedPeer = adapter.isPeerConnected(user.getUsername());
        if (connectedPeer || adapter.isServerConnected()) adapter.selectPeer(user.getUsername());

        targetNameLabel.setText(user.getUsername());
        targetInitials.setText(user.getInitials());
        targetAvatar.setStyle("-fx-background-color: " + JavaFxUtils.getAvatarColor(user.getUsername()) + ";");
        targetStatusDot.setVisible(true);
        targetStatusDot.getStyleClass().clear();
        targetStatusDot.getStyleClass().add(user.isOnline() ? "status-dot-online" : "status-dot-offline");
        updateDirectPeerStatus();
        if (!connectedPeer && adapter.isServerConnected()) targetMetaLabel.setText("Connecting...");

        joinLeaveCommunityBtn.setVisible(false);
        joinLeaveCommunityBtn.setManaged(false);

        emptyStateView.setVisible(false);
        emptyStateView.setManaged(false);
        messageScrollPane.setVisible(true);
        messageScrollPane.setManaged(true);

        inputContainer.setDisable(!connectedPeer);
        messageInput.setPromptText("Type to chat");

        loadConversation(ConversationStore.directKey(user.getUsername()));
        Platform.runLater(() -> messageInput.requestFocus());
    }

    private void showGlobalConversation() {
        unreadCounts.remove(ConversationStore.globalKey());
        updateUnreadBadges();
        targetNameLabel.setText("Global Chat");
        targetInitials.setText("G");
        targetAvatar.setStyle("-fx-background-color: #F59E0B;");
        targetStatusDot.setVisible(false);
        targetMetaLabel.setText("");
        joinLeaveCommunityBtn.setVisible(false);
        joinLeaveCommunityBtn.setManaged(false);
        emptyStateView.setVisible(false);
        emptyStateView.setManaged(false);
        messageScrollPane.setVisible(true);
        messageScrollPane.setManaged(true);
        inputContainer.setDisable(!adapter.isServerConnected() || adapter.isReconnecting());
        attachBtn.setDisable(true);
        messageInput.setPromptText("Type to chat to everyone online");
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
        messageView.clear();
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
        messageInput.clear();
        clearPendingAttachments();

        if (!text.isEmpty()) {
            ChatMessageModel msg = new ChatMessageModel(adapter.getCurrentUsername(), text, true);
            msg.sending();
            conversations.add(convKey, msg);
            messageView.render(msg);
            if (direct) {
                String messageId = UUID.randomUUID().toString();
                msg.setMessageId(messageId);
                trackPendingReceipt(messageId, target, msg);
                adapter.sendChatMessage(target, text, messageId, error -> {
                    msg.sent(error);
                    if (error != null) clearPendingReceipt(messageId);
                    else {
                        PendingReceipt pending = pendingReceipts.get(messageId);
                        if (pending != null) pending.timeout().playFromStart();
                    }
                });
            } else if (global) {
                adapter.broadcastGlobalMessage(text, msg::globalSent);
            } else {
                adapter.broadcastMessage(target, text, msg::broadcastSent);
            }
        }

        for (File file : attachments) {
            if (direct) adapter.sendFile(target, file);
            else if (!global) adapter.broadcastFile(target, communityName, file);
        }
        scrollToBottom();
    }




    private void trackPendingReceipt(String messageId, String peer, ChatMessageModel message) {
        PauseTransition timeout = new PauseTransition(Duration.seconds(30));
        timeout.setOnFinished(event -> {
            PendingReceipt pending = pendingReceipts.remove(messageId);
            if (pending != null) pending.message().unconfirmed();
        });
        pendingReceipts.put(messageId, new PendingReceipt(peer, message, timeout));
    }

    private void clearPendingReceipt(String messageId) {
        PendingReceipt pending = pendingReceipts.remove(messageId);
        if (pending != null) pending.timeout().stop();
    }

    private void clearPendingReceipts(String peer) {
        for (var entry : new ArrayList<>(pendingReceipts.entrySet())) {
            if (entry.getValue().peer().equalsIgnoreCase(peer)) {
                entry.getValue().message().unconfirmed();
                clearPendingReceipt(entry.getKey());
            }
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
        for (CommunityModel community : communities) {
            community.setUnreadCount(unreadCounts.getOrDefault(ConversationStore.communityKey(community.getId()), 0));
        }
        syncingCommunityList = true;
        try {
            if (!allCommunities.equals(communities)) allCommunities.setAll(communities);
        } finally {
            syncingCommunityList = false;
        }
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
            if (currentMode == Mode.EXPLORE) {
                selectNavigationTab(Mode.COMMUNITY);
            }
        }
        applyFilter(searchField.getText());
    }

    @Override
    public void onCommunityLeft(String communityId) {
        unreadCounts.remove(ConversationStore.communityKey(communityId));
        updateUnreadBadges();
        for (CommunityModel c : allCommunities) {
            if (c.getId().equals(communityId)) {
                c.setJoined(false);
                c.clearUnread();
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
            boolean peerConnected = adapter.isPeerConnected(existing.getUsername());
            if ((peerConnected || existing.getUnreadCount() > 0)
                    && updated.stream().noneMatch(user -> user.getUsername().equals(existing.getUsername()))) {
                existing.setOnline(peerConnected);
                if (peerConnected) existing.setStatusMessage("Peer connected");
                updated.add(existing);
            }
        }
        for (UserModel user : updated) {
            user.setUnreadCount(unreadCounts.getOrDefault(ConversationStore.directKey(user.getUsername()), 0));
        }
        syncingUserList = true;
        try {
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
                boolean peerConnected = adapter.isPeerConnected(activePeer.getUsername());
                targetStatusDot.getStyleClass().setAll(peerConnected ? "status-dot-online" : "status-dot-offline");
                updateDirectPeerStatus();
            }
        } finally {
            syncingUserList = false;
        }
    }

    @Override
    public void onPeerConnected(String peerUsername) {
        onUserListUpdated(adapter.getLastKnownOnlineUsers());
        if (currentMode == Mode.DIRECT && activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            updateDirectPeerStatus();
            targetStatusDot.setVisible(true);
            targetStatusDot.getStyleClass().clear();
            targetStatusDot.getStyleClass().add("status-dot-online");
            inputContainer.setDisable(false);
        }
    }

    @Override
    public void onPeerConnectionFailed(String peerUsername, String reason) {
        if (currentMode == Mode.DIRECT && activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            if (adapter.isServerConnected()) targetMetaLabel.setText("Connection failed: " + reason);
            else updateDirectPeerStatus();
        }
    }

    @Override
    public void onMessageReceived(String peerUsername, String message) {
        appendMessage(ConversationStore.directKey(peerUsername), new ChatMessageModel(peerUsername, message, false));
    }

    @Override
    public void onMessageDelivered(String peerUsername, String messageId) {
        PendingReceipt pending = pendingReceipts.get(messageId);
        if (pending != null) {
            if (!pending.peer().equalsIgnoreCase(peerUsername)) return;
            clearPendingReceipt(messageId);
            pending.message().delivered();
        } else {
            conversations.history(ConversationStore.directKey(peerUsername)).stream()
                    .filter(message -> message.isMine() && messageId.equals(message.getMessageId()))
                    .findFirst().ifPresent(ChatMessageModel::delivered);
        }
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
            int count = unreadCounts.merge(key, 1, Integer::sum);
            if (key.startsWith("peer-") && allUsers.stream()
                    .noneMatch(user -> ConversationStore.directKey(user.getUsername()).equals(key))) {
                String peer = key.substring("peer-".length());
                allUsers.add(new UserModel(peer, adapter.isPeerConnected(peer)));
            }
            allUsers.stream().filter(user -> ConversationStore.directKey(user.getUsername()).equals(key))
                    .findFirst().ifPresent(user -> user.setUnreadCount(count));
            allCommunities.stream().filter(group -> ConversationStore.communityKey(group.getId()).equals(key))
                    .findFirst().ifPresent(group -> group.setUnreadCount(count));
            usersListView.refresh();
            communitiesListView.refresh();
            updateUnreadBadges();
        }
    }

    private void updateUnreadBadges() {
        int direct = 0;
        int community = 0;
        int global = 0;
        for (Map.Entry<String, Integer> entry : unreadCounts.entrySet()) {
            if (entry.getKey().startsWith("peer-")) direct += entry.getValue();
            else if (entry.getKey().startsWith("comm-")) community += entry.getValue();
            else if (entry.getKey().equals(ConversationStore.globalKey())) global += entry.getValue();
        }
        updateUnreadBadge(directUnreadBadge, direct, "Direct Messages");
        updateUnreadBadge(communityUnreadBadge, community, "My Communities");
        updateUnreadBadge(globalUnreadBadge, global, "Global Chat");
    }

    private void updateUnreadBadge(Label badge, int count, String title) {
        badge.setText(count > 99 ? "99+" : Integer.toString(count));
        badge.setAccessibleText(title + ": " + count + " unread messages");
        badge.setVisible(count > 0);
        badge.setManaged(count > 0);
    }

    @Override
    public void onPeerDisconnected(String peerUsername) {
        clearPendingReceipts(peerUsername);
        onUserListUpdated(adapter.getLastKnownOnlineUsers());
        if (currentMode == Mode.DIRECT && activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            updateDirectPeerStatus();
            inputContainer.setDisable(true);
        }
    }

    @Override
    public void onConnectionClosed() {
        updateConnectionState();
    }

    @FXML
    private void handleReconnect() {
        if (adapter.isServerConnected() || adapter.isReconnecting()) return;
        JavaFxUtils.showPasswordDialog(titleBar.getScene().getWindow(), "Reconnect",
                "Password for " + adapter.getCurrentUsername()).ifPresent(adapter::reconnect);
    }

    private void updateConnectionState() {
        boolean ready = adapter.isServerConnected() && !adapter.isReconnecting();
        connectionView.render(adapter.connectionState(), currentMode != Mode.DIRECT);
        addCommunityBtn.setDisable(!ready);
        joinLeaveCommunityBtn.setDisable(!ready);
        if (currentMode == Mode.DIRECT) {
            inputContainer.setDisable(activePeer == null || !adapter.isPeerConnected(activePeer.getUsername()));
            updateDirectPeerStatus();
        } else if (currentMode == Mode.GLOBAL) {
            inputContainer.setDisable(!ready);
            attachBtn.setDisable(true);
        } else {
            inputContainer.setDisable(!ready || activeCommunity == null || !activeCommunity.isJoined());
            attachBtn.setDisable(false);
        }
    }

    private void updateDirectPeerStatus() {
        if (currentMode != Mode.DIRECT || activePeer == null) return;
        boolean peerConnected = adapter.isPeerConnected(activePeer.getUsername());
        targetStatusDot.getStyleClass().setAll(peerConnected ? "status-dot-online" : "status-dot-offline");
        targetMetaLabel.setText(peerConnected ? "Online" : "Offline");
    }

    @Override public void onReconnecting() { updateConnectionState(); }
    @Override public void onReconnected() {
        updateConnectionState();
        if (currentMode != Mode.DIRECT && activeCommunity != null && activeCommunity.isJoined()) {
            adapter.selectCommunity(activeCommunity.getId());
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
