package ui.view;

import java.util.function.Consumer;
import java.time.Duration;
import javafx.beans.binding.Bindings;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.KeyCode;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import ui.model.ChatMessageModel;
import ui.model.FileOfferModel;
import ui.util.IconUtils;
import ui.util.LocalFileOpener;
import ui.util.JavaFxUtils;

/** Renders message bubbles and file cards without owning session state. */
public final class ChatMessageView {
    private final VBox container;
    private final Consumer<String> accept;
    private final Consumer<String> cancel;
    private ChatMessageModel previousMessage;
    private VBox activeGroup;

    public void clear() {
        container.getChildren().clear();
        previousMessage = null;
        activeGroup = null;
    }


    private boolean continuesGroup(ChatMessageModel message) {
        if (previousMessage == null || activeGroup == null || previousMessage.isSystem()
                || previousMessage.isFileOffer() != message.isFileOffer()
                || previousMessage.isMine() != message.isMine()
                || !previousMessage.getSender().equals(message.getSender())) return false;
        Duration gap = Duration.between(previousMessage.getCreatedAt(), message.getCreatedAt());
        return !gap.isNegative() && gap.compareTo(Duration.ofMinutes(5)) < 0;
    }

    public ChatMessageView(VBox container, Consumer<String> accept, Consumer<String> cancel) {
        this.container = container;
        this.accept = accept;
        this.cancel = cancel;
    }

    public void render(ChatMessageModel msg) {
        if (msg.isSystem()) {
            HBox systemRow = new HBox();
            systemRow.setAlignment(Pos.CENTER);
            Label sysLbl = new Label(msg.getContent());
            sysLbl.getStyleClass().add("bubble-system");
            sysLbl.getStyleClass().add("bubble-system-text");
            systemRow.getChildren().add(sysLbl);
            container.getChildren().add(systemRow);
            previousMessage = null;
            activeGroup = null;
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
            Label status = new Label();
            status.textProperty().bind(msg.sendStatusProperty());
            Tooltip statusTooltip = new Tooltip();
            statusTooltip.textProperty().bind(msg.sendStatusDetailProperty());
            status.setTooltip(statusTooltip);
            status.getStyleClass().add("bubble-timestamp");
            status.setWrapText(true);
            status.setVisible(false);
            status.managedProperty().bind(status.visibleProperty());
            contentLbl.setCursor(javafx.scene.Cursor.HAND);
            contentLbl.setOnMouseClicked(event -> {
                if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY
                        && !status.getText().isEmpty()) {
                    status.setVisible(!status.isVisible());
                }
            });
            messageBubbleBox.getChildren().addAll(contentLbl, timeLbl, status);

        } else {
            contentLbl.getStyleClass().add("bubble-received");
            contentLbl.getStyleClass().add("bubble-received-text");

            messageBubbleBox.setAlignment(Pos.TOP_LEFT);
            messageBubbleBox.getChildren().addAll(contentLbl, timeLbl);

        }
        appendBubble(msg, messageBubbleBox);
    }

    private void appendBubble(ChatMessageModel msg, VBox bubble) {
        if (!continuesGroup(msg)) {
            activeGroup = new VBox(4);
            activeGroup.setAlignment(msg.isMine() ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
            activeGroup.setMaxWidth(480);
            HBox row = new HBox(8);
            row.setAlignment(msg.isMine() ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
            if (!msg.isMine()) row.getChildren().add(createAvatar(msg.getSender()));
            row.getChildren().add(activeGroup);
            if (msg.isMine() && msg.isFileOffer()) row.getChildren().add(createAvatar(msg.getSender()));
            container.getChildren().add(row);
        }
        activeGroup.getChildren().add(bubble);
        previousMessage = msg;
    }

    private static StackPane createAvatar(String sender) {
        StackPane avatar = new StackPane();
        avatar.setMinSize(28, 28);
        avatar.setPrefSize(28, 28);
        avatar.setMaxSize(28, 28);
        avatar.getStyleClass().add("avatar-circle");
        avatar.setStyle("-fx-background-color: " + JavaFxUtils.getAvatarColor(sender) + ";");
        String initials = sender.length() <= 2 ? sender.toUpperCase() : sender.substring(0, 2).toUpperCase();
        Label label = new Label(initials);
        label.setStyle("-fx-font-size: 10px; -fx-text-fill: white; -fx-font-weight: bold;");
        avatar.getChildren().add(label);
        Tooltip.install(avatar, new Tooltip(sender));
        return avatar;
    }

    private void renderFileOfferCard(ChatMessageModel msg) {
        FileOfferModel offer = msg.getFileOffer();
        if (offer == null) return;

        VBox card = new VBox(8);
        card.getStyleClass().add("file-card");
        card.setMaxWidth(400.0);
        card.setPickOnBounds(true);
        card.cursorProperty().bind(Bindings.when(offer.localPathProperty().isNotEmpty())
                .then(Cursor.HAND).otherwise(Cursor.DEFAULT));
        card.focusTraversableProperty().bind(offer.localPathProperty().isNotEmpty());
        card.setAccessibleText("Open folder containing " + offer.getFileName());
        card.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && !isButtonTarget(event.getTarget())
                    && !offer.getLocalPath().isBlank()) {
                openDownloadedFolder(offer.getLocalPath());
                event.consume();
            }
        });
        card.setOnKeyPressed(event -> {
            if ((event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE)
                    && !isButtonTarget(event.getTarget()) && !offer.getLocalPath().isBlank()) {
                openDownloadedFolder(offer.getLocalPath());
                event.consume();
            }
        });
        MenuItem openFile = new MenuItem("Open file");
        openFile.setOnAction(event -> openDownloadedFile(offer.getLocalPath()));
        MenuItem openFolder = new MenuItem("Open containing folder");
        openFolder.setOnAction(event -> openDownloadedFolder(offer.getLocalPath()));
        ContextMenu fileMenu = new ContextMenu(openFile, openFolder);
        card.setOnContextMenuRequested(event -> {
            if (!offer.getLocalPath().isBlank() && !isButtonTarget(event.getTarget())) {
                fileMenu.show(card, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });

        HBox topRow = new HBox(10);
        topRow.setAlignment(Pos.CENTER_LEFT);

        StackPane fileIcon = IconUtils.createIconNode(IconUtils.FILE, 24, "#F59E0B");
        VBox fileInfo = new VBox(2);
        fileInfo.setMinWidth(0);
        HBox.setHgrow(fileInfo, Priority.ALWAYS);
        Label nameLbl = new Label(offer.getFileName());
        nameLbl.getStyleClass().add("file-card-title");
        nameLbl.setMinWidth(0);
        nameLbl.setMaxWidth(Double.MAX_VALUE);
        Tooltip fileTooltip = new Tooltip();
        fileTooltip.textProperty().bind(Bindings.when(offer.localPathProperty().isNotEmpty())
                .then(offer.getFileName() + "\nClick to open the containing folder; right-click to open the file").otherwise(offer.getFileName()));
        nameLbl.setTooltip(fileTooltip);
        nameLbl.cursorProperty().bind(Bindings.when(offer.localPathProperty().isNotEmpty())
                .then(Cursor.HAND).otherwise(Cursor.DEFAULT));
        Label metaLbl = new Label(offer.getFormattedSize());
        metaLbl.getStyleClass().add("file-card-meta");
        Label fileStatus = new Label();
        fileStatus.textProperty().bind(offer.statusTextProperty());
        fileStatus.getStyleClass().add("file-card-meta");
        fileStatus.setWrapText(true);
        fileInfo.getChildren().addAll(nameLbl, metaLbl, fileStatus);
        topRow.getChildren().addAll(fileIcon, fileInfo);

        Label statusIcon = new Label();
        statusIcon.graphicProperty().bind(Bindings.createObjectBinding(() -> {
            String icon = switch (offer.getStatus()) {
                case PENDING, EXPIRED -> IconUtils.CLOCK;
                case ACCEPTED, TRANSFERRING, CANCELING -> IconUtils.REFRESH;
                case COMPLETED -> IconUtils.CHECK;
                case FAILED, REJECTED, CANCELED -> IconUtils.X;
            };
            String color = switch (offer.getStatus()) {
                case FAILED -> "#DC2626";
                case COMPLETED -> "#10B981";
                default -> "#71717A";
            };
            return IconUtils.createIconNode(icon, 18, color);
        }, offer.statusProperty()));
        Tooltip statusTooltip = new Tooltip();
        statusTooltip.textProperty().bind(Bindings.createStringBinding(() -> switch (offer.getStatus()) {
            case PENDING -> offer.isIncoming() ? "Waiting to download" : "File offer sent. Waiting for the recipient to download.";
            case ACCEPTED -> "Preparing download";
            case TRANSFERRING -> "Transferring file";
            case COMPLETED -> offer.isIncoming() ? "Downloaded" : "Sent";
            case EXPIRED -> "File offer expired";
            case REJECTED -> "File declined";
            case CANCELING -> "Canceling and removing the partial file";
            case CANCELED -> "File transfer canceled";
            case FAILED -> "Unable to transfer file: " + offer.getStatusText();
        }, offer.statusProperty(), offer.statusTextProperty()));
        statusIcon.setTooltip(statusTooltip);
        statusIcon.accessibleTextProperty().bind(statusTooltip.textProperty());
        statusIcon.visibleProperty().bind(Bindings.createBooleanBinding(() -> switch (offer.getStatus()) {
            case FAILED, REJECTED, CANCELED, EXPIRED, CANCELING -> true;
            case PENDING -> !offer.isIncoming() && offer.getLocalPath().isBlank();
            default -> offer.getLocalPath().isBlank();
        }, offer.statusProperty(), offer.localPathProperty(), offer.incomingProperty()));
        statusIcon.managedProperty().bind(statusIcon.visibleProperty());
        topRow.getChildren().add(statusIcon);

        ProgressBar pb = new ProgressBar(0.0);
        pb.progressProperty().bind(offer.progressProperty());
        pb.setMaxWidth(Double.MAX_VALUE);
        pb.visibleProperty().bind(offer.statusProperty().isEqualTo(FileOfferModel.Status.TRANSFERRING));
        pb.managedProperty().bind(pb.visibleProperty());

        Label statistics = new Label();
        statistics.textProperty().bind(offer.transferStatsProperty());
        statistics.getStyleClass().add("file-card-meta");
        statistics.visibleProperty().bind(offer.statusProperty().isEqualTo(FileOfferModel.Status.TRANSFERRING));
        statistics.managedProperty().bind(statistics.visibleProperty());

        Button cancelButton = fileButton("", "btn-secondary");
        cancelButton.setGraphic(IconUtils.createIconNode(IconUtils.X, 18, "#71717A"));
        cancelButton.setTooltip(new Tooltip("Cancel file transfer"));
        cancelButton.setAccessibleText("Cancel transfer of " + offer.getFileName());
        cancelButton.visibleProperty().bind(offer.statusProperty().isEqualTo(FileOfferModel.Status.ACCEPTED)
                .or(offer.statusProperty().isEqualTo(FileOfferModel.Status.TRANSFERRING)));
        cancelButton.setOnAction(event -> {
            offer.setStatus(FileOfferModel.Status.CANCELING);
            cancel.accept(offer.getOfferNumber());
        });
        topRow.getChildren().add(cancelButton);

        if (offer.isIncoming()) {
            Button downloadButton = fileButton("", "btn-secondary");
            downloadButton.setGraphic(IconUtils.createIconNode(IconUtils.DOWNLOAD, 18, "#18181B"));
            downloadButton.setTooltip(new Tooltip("Download"));
            downloadButton.setAccessibleText("Download " + offer.getFileName());
            downloadButton.visibleProperty().bind(offer.statusProperty().isEqualTo(FileOfferModel.Status.PENDING));
            downloadButton.setOnAction(event -> {
                if (offer.getStatus() != FileOfferModel.Status.PENDING) return;
                offer.setStatus(FileOfferModel.Status.ACCEPTED);
                accept.accept(offer.getOfferNumber());
            });
            topRow.getChildren().add(downloadButton);
        }
        Button folderButton = fileButton("", "btn-secondary");
        folderButton.setGraphic(IconUtils.createIconNode(IconUtils.FOLDER, 18, "#18181B"));
        folderButton.setTooltip(new Tooltip("Open containing folder"));
        folderButton.setAccessibleText("Open folder containing " + offer.getFileName());
        folderButton.visibleProperty().bind(offer.localPathProperty().isNotEmpty());
        folderButton.setOnAction(event -> openDownloadedFolder(offer.getLocalPath()));
        topRow.getChildren().add(folderButton);

        card.getChildren().addAll(topRow, pb, statistics);

        Label time = new Label(msg.getTimestamp());
        time.getStyleClass().add("bubble-timestamp");
        VBox bubble = new VBox(3, card, time);
        bubble.setMaxWidth(400);
        bubble.setAlignment(msg.isMine() ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        appendBubble(msg, bubble);
    }

    private static boolean isButtonTarget(Object target) {
        Node node = target instanceof Node ? (Node) target : null;
        while (node != null) {
            if (node instanceof ButtonBase) return true;
            node = node.getParent();
        }
        return false;
    }

    private void openDownloadedFile(String path) {
        LocalFileOpener.open(container.getScene() == null ? null : container.getScene().getWindow(), path, false);
    }

    private void openDownloadedFolder(String path) {
        LocalFileOpener.open(container.getScene() == null ? null : container.getScene().getWindow(), path, true);
    }

    private static Button fileButton(String text, String style) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        button.setStyle("-fx-font-size: 11px; -fx-padding: 5px 12px; -fx-cursor: hand;");
        button.managedProperty().bind(button.visibleProperty());
        return button;
    }
}
