package ui.controller;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
import javafx.scene.control.Button;
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
import javafx.stage.Window;
import ui.adapter.ChatClientAdapter;
import ui.adapter.ClientEventListener;
import ui.model.ChatMessageModel;
import ui.model.CommunityModel;
import ui.model.FileOfferModel;
import ui.model.UserModel;
import ui.util.IconUtils;
import ui.util.JavaFxUtils;
import ui.util.WindowDragHelper;

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

    private final Map<String, ObservableList<ChatMessageModel>> conversations = new ConcurrentHashMap<>();

    private enum Mode { COMMUNITY, EXPLORE, DIRECT }
    private Mode currentMode = Mode.COMMUNITY;

    private CommunityModel activeCommunity = null;
    private UserModel activePeer = null;

    @FXML
    public void initialize() {
        adapter.addListener(this);

        setupTitleBar();
        setupCurrentUserDisplay();
        setupLists();
        setupIcons();
        handleSelectCommunitiesTab();

        onUserListUpdated(adapter.getLastKnownOnlineUsers());
        onCommunityListUpdated(adapter.getLastKnownCommunities());

        adapter.requestUserList();
        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(() -> {
            adapter.requestCommunityList();
        });
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

        communitiesListView.setCellFactory(lv -> new ListCell<>() {
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
        });

        communitiesListView.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                selectCommunity(newV);
            }
        });

        Label noUsers = new Label("No other users online");
        noUsers.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 12px;");
        usersListView.setPlaceholder(noUsers);

        filteredUsers = new FilteredList<>(allUsers, p -> true);
        usersListView.setItems(filteredUsers);

        usersListView.setCellFactory(lv -> new ListCell<>() {
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
        });

        usersListView.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
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
        activeCommunity = null;
        activePeer = null;
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
    private void handleSelectCommunitiesTab() {
        currentMode = Mode.COMMUNITY;
        if (railCommunitiesBtn != null) {
            railCommunitiesBtn.getStyleClass().setAll("rail-btn-active");
            railCommunitiesBtn.setGraphic(IconUtils.createIconNode(IconUtils.CHAT_BUBBLE, 18, "#09090B"));
        }
        if (railExploreBtn != null) {
            railExploreBtn.getStyleClass().setAll("rail-btn");
            railExploreBtn.setGraphic(IconUtils.createIconNode(IconUtils.COMPASS, 18, "#A1A1AA"));
        }
        if (railDirectBtn != null) {
            railDirectBtn.getStyleClass().setAll("rail-btn");
            railDirectBtn.setGraphic(IconUtils.createIconNode(IconUtils.USER, 18, "#A1A1AA"));
        }

        sidebarSectionTitle.setText("MY COMMUNITIES");
        searchField.setPromptText("Search my communities...");

        communitiesListView.setVisible(true);
        communitiesListView.setManaged(true);
        usersListView.setVisible(false);
        usersListView.setManaged(false);

        adapter.requestCommunityList();
        applyFilter(searchField.getText());

        if (activeCommunity != null && activeCommunity.isJoined()) {
            selectCommunity(activeCommunity);
        }
    }

    @FXML
    private void handleSelectExploreTab() {
        currentMode = Mode.EXPLORE;
        if (railExploreBtn != null) {
            railExploreBtn.getStyleClass().setAll("rail-btn-active");
            railExploreBtn.setGraphic(IconUtils.createIconNode(IconUtils.COMPASS, 18, "#09090B"));
        }
        if (railCommunitiesBtn != null) {
            railCommunitiesBtn.getStyleClass().setAll("rail-btn");
            railCommunitiesBtn.setGraphic(IconUtils.createIconNode(IconUtils.CHAT_BUBBLE, 18, "#A1A1AA"));
        }
        if (railDirectBtn != null) {
            railDirectBtn.getStyleClass().setAll("rail-btn");
            railDirectBtn.setGraphic(IconUtils.createIconNode(IconUtils.USER, 18, "#A1A1AA"));
        }

        sidebarSectionTitle.setText("EXPLORE COMMUNITIES");
        searchField.setPromptText("Search all communities...");

        communitiesListView.setVisible(true);
        communitiesListView.setManaged(true);
        usersListView.setVisible(false);
        usersListView.setManaged(false);

        adapter.requestCommunityList();
        applyFilter(searchField.getText());
    }

    @FXML
    private void handleSelectDirectTab() {
        currentMode = Mode.DIRECT;
        if (railDirectBtn != null) {
            railDirectBtn.getStyleClass().setAll("rail-btn-active");
            railDirectBtn.setGraphic(IconUtils.createIconNode(IconUtils.USER, 18, "#09090B"));
        }
        if (railCommunitiesBtn != null) {
            railCommunitiesBtn.getStyleClass().setAll("rail-btn");
            railCommunitiesBtn.setGraphic(IconUtils.createIconNode(IconUtils.CHAT_BUBBLE, 18, "#A1A1AA"));
        }
        if (railExploreBtn != null) {
            railExploreBtn.getStyleClass().setAll("rail-btn");
            railExploreBtn.setGraphic(IconUtils.createIconNode(IconUtils.COMPASS, 18, "#A1A1AA"));
        }

        sidebarSectionTitle.setText("ONLINE USERS");
        searchField.setPromptText("Search online users...");

        usersListView.setVisible(true);
        usersListView.setManaged(true);
        communitiesListView.setVisible(false);
        communitiesListView.setManaged(false);

        adapter.requestUserList();
        applyFilter(searchField.getText());

        if (activePeer != null) {
            selectPeerUser(activePeer);
        }
    }

    @FXML
    private void handleShowDirectMessages() {
        handleSelectDirectTab();
    }

    private boolean isCreatingCommunity = false;

    @FXML
    private void handleOpenCreateCommunityDialog() {
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

        adapter.selectCommunity(comm.getId());

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

        inputContainer.setDisable(!comm.isJoined());
        messageInput.setPromptText(comm.isJoined() ? "Broadcast to #" + comm.getName() + "..." : "Join community to participate...");

        loadConversation("comm-" + comm.getId());
        Platform.runLater(() -> messageInput.requestFocus());
    }

    private void selectPeerUser(UserModel user) {
        this.activePeer = user;
        user.clearUnread();
        usersListView.refresh();

        adapter.selectPeer(user.getUsername());

        targetNameLabel.setText(user.getUsername());
        targetInitials.setText(user.getInitials());
        targetAvatar.setStyle("-fx-background-color: " + JavaFxUtils.getAvatarColor(user.getUsername()) + ";");
        targetStatusDot.setVisible(true);
        targetStatusDot.getStyleClass().clear();
        targetStatusDot.getStyleClass().add(user.isOnline() ? "status-dot-online" : "status-dot-offline");
        targetMetaLabel.setText(user.isOnline() ? "Direct P2P Session Active" : "User Offline");

        joinLeaveCommunityBtn.setVisible(false);
        joinLeaveCommunityBtn.setManaged(false);

        emptyStateView.setVisible(false);
        emptyStateView.setManaged(false);
        messageScrollPane.setVisible(true);
        messageScrollPane.setManaged(true);

        inputContainer.setDisable(!user.isOnline());
        messageInput.setPromptText("Message @" + user.getUsername() + "...");

        loadConversation("peer-" + user.getUsername());
        Platform.runLater(() -> messageInput.requestFocus());
    }

    private void updateJoinLeaveButton(boolean isJoined) {
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
        if (activeCommunity == null) return;
        if (activeCommunity.isJoined()) {
            Window win = titleBar != null && titleBar.getScene() != null ? titleBar.getScene().getWindow() : null;
            boolean confirm = JavaFxUtils.showConfirmDialog(
                win,
                "Leave Community",
                "Are you sure you want to leave #" + activeCommunity.getName() + "?"
            );
            if (!confirm) return;

            adapter.leaveCommunity(activeCommunity.getId());
            activeCommunity.setJoined(false);
            updateJoinLeaveButton(false);
            inputContainer.setDisable(true);
            messageInput.setPromptText("Join community to participate in broadcast...");

            String q = searchField.getText();
            if (q == null || q.trim().isEmpty()) {
                applyFilter("");
                showEmptyChatState();
            } else {
                applyFilter(q);
            }
        } else {
            adapter.joinCommunity(activeCommunity.getId());
            activeCommunity.setJoined(true);
            updateJoinLeaveButton(true);
            inputContainer.setDisable(false);
            messageInput.setPromptText("Broadcast to #" + activeCommunity.getName() + "...");

            String q = searchField.getText();
            applyFilter(q);
        }
    }

    private void loadConversation(String convKey) {
        messagesContainer.getChildren().clear();
        ObservableList<ChatMessageModel> history = conversations.computeIfAbsent(
                convKey, k -> FXCollections.observableArrayList()
        );
        for (ChatMessageModel msg : history) {
            renderMessageNode(msg);
        }
        scrollToBottom();
    }

    @FXML
    private void handleSendMessage() {
        String text = messageInput.getText().trim();
        if (text.isEmpty()) return;
        messageInput.clear();

        if (currentMode == Mode.COMMUNITY && activeCommunity != null) {
            adapter.broadcastMessage(text);
            ChatMessageModel msg = new ChatMessageModel(adapter.getCurrentUsername(), text, true);
            String convKey = "comm-" + activeCommunity.getId();
            conversations.computeIfAbsent(convKey, k -> FXCollections.observableArrayList()).add(msg);
            renderMessageNode(msg);
            scrollToBottom();
        } else if (currentMode == Mode.DIRECT && activePeer != null) {
            adapter.sendChatMessage(activePeer.getUsername(), text);
            ChatMessageModel msg = new ChatMessageModel(adapter.getCurrentUsername(), text, true);
            String convKey = "peer-" + activePeer.getUsername();
            conversations.computeIfAbsent(convKey, k -> FXCollections.observableArrayList()).add(msg);
            renderMessageNode(msg);
            scrollToBottom();
        }
    }

    @FXML
    private void handleAttachFile() {
        FileChooser chooser = new FileChooser();
        String title = (currentMode == Mode.COMMUNITY)
                ? "Select File to Broadcast to #" + (activeCommunity != null ? activeCommunity.getName() : "Community")
                : "Select File to Send to @" + (activePeer != null ? activePeer.getUsername() : "User");
        chooser.setTitle(title);

        Stage stage = (Stage) attachBtn.getScene().getWindow();
        File file = chooser.showOpenDialog(stage);

        if (file != null) {
            if (file.length() > 500L * 1024 * 1024) {
                JavaFxUtils.showErrorAlert("File Too Large", "Maximum supported file size is 100 MiB.");
                return;
            }

            if (currentMode == Mode.COMMUNITY && activeCommunity != null) {
                adapter.broadcastFile(file);
                FileOfferModel offer = new FileOfferModel(
                        "bc-out-" + System.currentTimeMillis(),
                        adapter.getCurrentUsername(),
                        activeCommunity.getName(),
                        file.getName(),
                        file.length(),
                        "",
                        false
                );
                offer.setBroadcast(true);
                offer.setCommunityId(activeCommunity.getId());
                offer.setStatus(FileOfferModel.Status.PENDING);
                offer.setStatusText("Uploading to community storage...");

                ChatMessageModel msg = ChatMessageModel.file(adapter.getCurrentUsername(), offer, true);
                conversations.computeIfAbsent("comm-" + activeCommunity.getId(), k -> FXCollections.observableArrayList()).add(msg);
                renderMessageNode(msg);
                scrollToBottom();

            } else if (currentMode == Mode.DIRECT && activePeer != null) {
                adapter.sendFile(activePeer.getUsername(), file);
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
                offer.setStatusText("Offered to @" + activePeer.getUsername() + ". Waiting for decision...");

                ChatMessageModel msg = ChatMessageModel.file(adapter.getCurrentUsername(), offer, true);
                conversations.computeIfAbsent("peer-" + activePeer.getUsername(), k -> FXCollections.observableArrayList()).add(msg);
                renderMessageNode(msg);
                scrollToBottom();
            }
        }
    }

    private void renderMessageNode(ChatMessageModel msg) {
        if (msg.isSystem()) {
            HBox systemRow = new HBox();
            systemRow.setAlignment(Pos.CENTER);
            Label sysLbl = new Label(msg.getContent());
            sysLbl.getStyleClass().add("bubble-system");
            sysLbl.getStyleClass().add("bubble-system-text");
            systemRow.getChildren().add(sysLbl);
            messagesContainer.getChildren().add(systemRow);
            return;
        }

        if (msg.isFileOffer()) {
            renderFileOfferCard(msg);
            return;
        }

        VBox messageBubbleBox = new VBox(3);
        messageBubbleBox.setMaxWidth(480.0);

        Label contentLbl = new Label(msg.getContent());
        contentLbl.setWrapText(true);

        Label timeLbl = new Label(msg.getTimestamp());
        timeLbl.getStyleClass().add("bubble-timestamp");

        if (msg.isMine()) {
            contentLbl.getStyleClass().add("bubble-sent");
            contentLbl.getStyleClass().add("bubble-sent-text");
            messageBubbleBox.setAlignment(Pos.TOP_RIGHT);
            timeLbl.setAlignment(Pos.CENTER_RIGHT);
            messageBubbleBox.getChildren().addAll(contentLbl, timeLbl);

            HBox row = new HBox(messageBubbleBox);
            row.setAlignment(Pos.CENTER_RIGHT);
            messagesContainer.getChildren().add(row);
        } else {
            contentLbl.getStyleClass().add("bubble-received");
            contentLbl.getStyleClass().add("bubble-received-text");

            Label senderLbl = new Label(msg.getSender());
            senderLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #003366;");

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

        VBox card = new VBox(8);
        card.getStyleClass().add("file-card");
        card.setMaxWidth(400.0);

        HBox topRow = new HBox(10);
        topRow.setAlignment(Pos.CENTER_LEFT);

        StackPane fileIcon = IconUtils.createIconNode(IconUtils.FILE, 24, "#F59E0B");
        VBox fileInfo = new VBox(2);
        Label nameLbl = new Label(offer.getFileName());
        nameLbl.getStyleClass().add("file-card-title");
        Label metaLbl = new Label(offer.getFormattedSize() + (offer.getChecksum().isEmpty() ? "" : " • " + offer.getChecksum().substring(0, Math.min(8, offer.getChecksum().length()))));
        metaLbl.getStyleClass().add("file-card-meta");
        fileInfo.getChildren().addAll(nameLbl, metaLbl);
        topRow.getChildren().addAll(fileIcon, fileInfo);

        Label statusLbl = new Label(offer.getStatusText());
        statusLbl.textProperty().bind(offer.statusTextProperty());
        statusLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #71717A;");

        ProgressBar pb = new ProgressBar(0.0);
        pb.progressProperty().bind(offer.progressProperty());
        pb.setMaxWidth(Double.MAX_VALUE);
        pb.visibleProperty().bind(offer.statusProperty().isEqualTo(FileOfferModel.Status.TRANSFERRING));
        pb.managedProperty().bind(pb.visibleProperty());

        HBox buttonBar = new HBox(8);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        Runnable updateButtons = () -> {
            buttonBar.getChildren().clear();
            boolean hasPath = offer.getLocalPath() != null && !offer.getLocalPath().isEmpty();

            if (offer.isIncoming() && offer.getStatus() == FileOfferModel.Status.PENDING) {
                Button rejectBtn = new Button("Reject");
                rejectBtn.getStyleClass().add("btn-secondary");
                rejectBtn.setStyle("-fx-font-size: 11px; -fx-padding: 5px 12px; -fx-cursor: hand;");
                rejectBtn.setOnAction(e -> {
                    adapter.rejectFileOffer(offer.getOfferNumber());
                    offer.setStatus(FileOfferModel.Status.REJECTED);
                    offer.setStatusText("Rejected");
                });

                Button acceptBtn = new Button(offer.isBroadcast() ? "Accept" : "Download");
                acceptBtn.getStyleClass().add("btn-primary");
                acceptBtn.setStyle("-fx-font-size: 11px; -fx-padding: 5px 14px; -fx-cursor: hand;");
                acceptBtn.setOnAction(e -> {
                    adapter.acceptFileOffer(offer.getOfferNumber());
                    offer.setStatus(FileOfferModel.Status.ACCEPTED);
                    offer.setStatusText(offer.isBroadcast() ? "Downloading from server..." : "Connecting P2P & downloading...");
                });

                buttonBar.getChildren().addAll(rejectBtn, acceptBtn);
            } else if (hasPath) {
                Button folderBtn = new Button("Folder");
                folderBtn.getStyleClass().add("btn-secondary");
                folderBtn.setStyle("-fx-font-size: 11px; -fx-padding: 5px 12px; -fx-cursor: hand;");
                folderBtn.setOnAction(e -> openDownloadedFolder(offer.getLocalPath()));

                Button openBtn = new Button("Open File");
                openBtn.getStyleClass().add("btn-primary");
                openBtn.setStyle("-fx-font-size: 11px; -fx-padding: 5px 14px; -fx-cursor: hand;");
                openBtn.setOnAction(e -> openDownloadedFile(offer.getLocalPath()));

                buttonBar.getChildren().addAll(folderBtn, openBtn);
            }
        };

        offer.statusProperty().addListener((obs, oldV, newV) -> JavaFxUtils.runOnFxThread(updateButtons));
        offer.localPathProperty().addListener((obs, oldV, newV) -> JavaFxUtils.runOnFxThread(updateButtons));
        updateButtons.run();

        card.getChildren().addAll(topRow, statusLbl, pb, buttonBar);

        HBox outer = new HBox(card);
        outer.setAlignment(msg.isMine() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        messagesContainer.getChildren().add(outer);
    }

    private void openDownloadedFile(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            File f = new File(path);
            if (f.exists() && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(f);
            } else if (f.getParentFile() != null && f.getParentFile().exists()) {
                Desktop.getDesktop().open(f.getParentFile());
            } else {
                JavaFxUtils.showInfoAlert("File Info", "File located at:\n" + path);
            }
        } catch (Exception ex) {
            try {
                File f = new File(path);
                if (f.getParentFile() != null && f.getParentFile().exists()) {
                    Desktop.getDesktop().open(f.getParentFile());
                }
            } catch (Exception ignored) {
                JavaFxUtils.showErrorAlert("Open File Error", "Cannot open file: " + ex.getMessage());
            }
        }
    }

    private void openDownloadedFolder(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            File f = new File(path);
            File dir = f.isDirectory() ? f : f.getParentFile();
            if (dir != null && dir.exists() && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            }
        } catch (Exception ex) {
            JavaFxUtils.showErrorAlert("Open Folder Error", "Cannot open folder: " + ex.getMessage());
        }
    }

    private FileOfferModel findFileOffer(String identifier) {
        if (identifier == null || identifier.isBlank()) return null;
        for (ObservableList<ChatMessageModel> list : conversations.values()) {
            for (ChatMessageModel msg : list) {
                if (msg.isFileOffer() && msg.getFileOffer() != null) {
                    FileOfferModel o = msg.getFileOffer();
                    if (identifier.equalsIgnoreCase(o.getFileName())
                            || identifier.equals(o.getOfferNumber())) {
                        return o;
                    }
                }
            }
        }
        return null;
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
        ChatClientAdapter.getInstance().disconnect();
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
        allCommunities.setAll(communities);
        if (activeCommunity != null) {
            for (CommunityModel c : allCommunities) {
                if (c.getId().equals(activeCommunity.getId())) {
                    activeCommunity = c;
                    updateJoinLeaveButton(c.isJoined());
                    break;
                }
            }
        }
        applyFilter(searchField.getText());
    }

    @Override
    public void onCommunityJoined(String communityId, String communityName) {
        for (CommunityModel c : allCommunities) {
            if (c.getId().equals(communityId) || c.getName().equalsIgnoreCase(communityName)) {
                c.setJoined(true);
                break;
            }
        }
        if (activeCommunity != null && (activeCommunity.getId().equals(communityId) || activeCommunity.getName().equalsIgnoreCase(communityName))) {
            activeCommunity.setJoined(true);
            updateJoinLeaveButton(true);
            inputContainer.setDisable(false);
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
        ChatMessageModel msg = new ChatMessageModel(sender, message, sender.equals(adapter.getCurrentUsername()));
        String convKey = "comm-" + (communityId != null ? communityId : (activeCommunity != null ? activeCommunity.getId() : "default"));
        conversations.computeIfAbsent(convKey, k -> FXCollections.observableArrayList()).add(msg);

        if (currentMode == Mode.COMMUNITY && activeCommunity != null && (communityId == null || activeCommunity.getId().equals(communityId))) {
            renderMessageNode(msg);
            scrollToBottom();
        } else {
            for (CommunityModel c : allCommunities) {
                if (c.getId().equals(communityId)) {
                    c.incrementUnread();
                    communitiesListView.refresh();
                    break;
                }
            }
        }
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
        allUsers.setAll(updated);
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
            targetMetaLabel.setText("Direct P2P Session Active");
            targetStatusDot.setVisible(true);
            targetStatusDot.getStyleClass().clear();
            targetStatusDot.getStyleClass().add("status-dot-online");
            inputContainer.setDisable(false);
        }
    }

    @Override
    public void onPeerOffline(String peerUsername) {
        if (activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            targetMetaLabel.setText("Peer is currently offline");
            targetStatusDot.setVisible(true);
            targetStatusDot.getStyleClass().clear();
            targetStatusDot.getStyleClass().add("status-dot-offline");
            inputContainer.setDisable(true);
        }
    }

    @Override
    public void onPeerConnectionFailed(String peerUsername, String reason) {
        if (activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            targetMetaLabel.setText("Connection failed: " + reason);
        }
    }

    @Override
    public void onMessageReceived(String peerUsername, String message) {
        ChatMessageModel msg = new ChatMessageModel(peerUsername, message, false);
        String convKey = "peer-" + peerUsername;
        conversations.computeIfAbsent(convKey, k -> FXCollections.observableArrayList()).add(msg);

        if (currentMode == Mode.DIRECT && activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            renderMessageNode(msg);
            scrollToBottom();
        } else {
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
        if (offer.isBroadcast()) {
            String commId = offer.getCommunityId();
            if ((commId == null || commId.isBlank()) && activeCommunity != null) {
                commId = activeCommunity.getId();
                offer.setCommunityId(commId);
            }
            if (commId == null || commId.isBlank()) {
                for (CommunityModel c : allCommunities) {
                    if (c.isJoined()) {
                        commId = c.getId();
                        offer.setCommunityId(commId);
                        break;
                    }
                }
            }

            offer.setStatusText("Community file shared. Accept to download or reject.");
            ChatMessageModel msg = ChatMessageModel.file(offer.getSender(), offer, false);
            String convKey = "comm-" + (commId != null && !commId.isBlank() ? commId : "default");
            conversations.computeIfAbsent(convKey, k -> FXCollections.observableArrayList()).add(msg);

            if (currentMode == Mode.COMMUNITY && activeCommunity != null && activeCommunity.getId().equals(commId)) {
                renderMessageNode(msg);
                scrollToBottom();
            } else {
                for (CommunityModel c : allCommunities) {
                    if (c.getId().equals(commId)) {
                        c.incrementUnread();
                        communitiesListView.refresh();
                        break;
                    }
                }
            }
            return;
        }

        String peer = offer.isIncoming() ? offer.getSender() : offer.getReceiver();
        ChatMessageModel msg = ChatMessageModel.file(offer.getSender(), offer, !offer.isIncoming());
        String convKey = "peer-" + peer;
        conversations.computeIfAbsent(convKey, k -> FXCollections.observableArrayList()).add(msg);

        if (currentMode == Mode.DIRECT && activePeer != null && activePeer.getUsername().equals(peer)) {
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
        FileOfferModel offer = findFileOffer(offerNumber);
        if (offer != null) {
            if (statusMessage.contains("ACCEPTED")) {
                offer.setStatus(FileOfferModel.Status.TRANSFERRING);
                offer.setStatusText("Transfer accepted. Transferring...");
            } else if (statusMessage.contains("REJECTED")) {
                offer.setStatus(FileOfferModel.Status.REJECTED);
                offer.setStatusText("Transfer rejected.");
            } else {
                offer.setStatusText(statusMessage);
            }
        }
    }

    @Override
    public void onFileTransferCompleted(String fileName, String detail, boolean isSent) {
        FileOfferModel offer = findFileOffer(fileName);
        if (offer != null) {
            offer.setStatus(FileOfferModel.Status.COMPLETED);
            offer.setStatusText(isSent ? (offer.isBroadcast() ? "Uploaded to community successfully" : "Sent successfully") : "Downloaded successfully");
            if (!isSent && detail != null && !detail.isBlank()) {
                offer.setLocalPath(detail);
            }
        }
    }

    @Override
    public void onFileTransferError(String error) {
        for (ObservableList<ChatMessageModel> list : conversations.values()) {
            for (ChatMessageModel msg : list) {
                if (msg.isFileOffer() && msg.getFileOffer() != null) {
                    FileOfferModel o = msg.getFileOffer();
                    if (o.getStatus() == FileOfferModel.Status.PENDING || o.getStatus() == FileOfferModel.Status.TRANSFERRING) {
                        o.setStatus(FileOfferModel.Status.FAILED);
                        o.setStatusText("Transfer error: " + error);
                    }
                }
            }
        }
    }

    @Override
    public void onPeerDisconnected(String peerUsername) {
        if (activePeer != null && activePeer.getUsername().equals(peerUsername)) {
            targetMetaLabel.setText("Peer disconnected");
            inputContainer.setDisable(true);
        }
    }

    @Override
    public void onConnectionClosed() {
        JavaFxUtils.showErrorAlert("Disconnected", "Server connection closed.");
    }

    @Override public void onSystemNotification(String message, boolean isError) {}
    @Override public void onLoginSuccess(String username) {}
    @Override public void onLoginFailed(String reason) {}
}
