package ui.view;

import java.awt.Desktop;
import java.io.File;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import ui.model.ChatMessageModel;
import ui.model.FileOfferModel;
import ui.util.IconUtils;
import ui.util.JavaFxUtils;

/** Renders message bubbles and file cards without owning session state. */
public final class ChatMessageView {
    private final VBox container;
    private final Consumer<String> accept;
    private final Consumer<String> reject;

    public ChatMessageView(VBox container, Consumer<String> accept, Consumer<String> reject) {
        this.container = container;
        this.accept = accept;
        this.reject = reject;
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
            status.getStyleClass().add("bubble-timestamp");
            status.setWrapText(true);
            messageBubbleBox.getChildren().addAll(contentLbl, timeLbl, status);

            HBox row = new HBox(messageBubbleBox);
            row.setAlignment(Pos.CENTER_RIGHT);
            container.getChildren().add(row);
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
            container.getChildren().add(row);
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

        if (offer.isIncoming()) {
            Button rejectButton = fileButton("Reject", "btn-secondary");
            Button acceptButton = fileButton(offer.isBroadcast() ? "Accept" : "Download", "btn-primary");
            rejectButton.visibleProperty().bind(offer.statusProperty().isEqualTo(FileOfferModel.Status.PENDING));
            acceptButton.visibleProperty().bind(rejectButton.visibleProperty());
            rejectButton.setOnAction(event -> {
                offer.setStatus(FileOfferModel.Status.ACCEPTED);
                offer.setStatusText("Rejecting...");
                reject.accept(offer.getOfferNumber());
            });
            acceptButton.setOnAction(event -> {
                offer.setStatus(FileOfferModel.Status.ACCEPTED);
                accept.accept(offer.getOfferNumber());
            });
            buttonBar.getChildren().addAll(rejectButton, acceptButton);
        }
        Button folderButton = fileButton("Folder", "btn-secondary");
        Button openButton = fileButton("Open File", "btn-primary");
        folderButton.visibleProperty().bind(offer.localPathProperty().isNotEmpty());
        openButton.visibleProperty().bind(folderButton.visibleProperty());
        folderButton.setOnAction(event -> openDownloadedFolder(offer.getLocalPath()));
        openButton.setOnAction(event -> openDownloadedFile(offer.getLocalPath()));
        buttonBar.getChildren().addAll(folderButton, openButton);

        card.getChildren().addAll(topRow, statusLbl, pb, buttonBar);

        HBox outer = new HBox(card);
        outer.setAlignment(msg.isMine() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        container.getChildren().add(outer);
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

    private static Button fileButton(String text, String style) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        button.setStyle("-fx-font-size: 11px; -fx-padding: 5px 12px; -fx-cursor: hand;");
        button.managedProperty().bind(button.visibleProperty());
        return button;
    }
}
