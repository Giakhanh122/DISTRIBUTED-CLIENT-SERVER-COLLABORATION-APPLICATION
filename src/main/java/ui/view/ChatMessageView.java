package ui.view;

import java.util.function.Consumer;
import java.time.LocalDate;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import javafx.beans.binding.Bindings;
import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
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
    private LocalDate renderedDate;

    public void clear() {
        container.getChildren().clear();
        previousMessage = null;
        activeGroup = null;
        renderedDate = null;
    }

    private void renderDate(ChatMessageModel message) {
        LocalDate date = message.getCreatedAt().toLocalDate();
        if (date.equals(renderedDate)) return;
        LocalDate today = LocalDate.now();
        String title = date.equals(today) ? "Hôm nay" : date.equals(today.minusDays(1)) ? "Hôm qua"
                : date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        Label label = new Label(title);
        label.getStyleClass().add("chat-date-label");
        Region left = new Region();
        Region right = new Region();
        left.getStyleClass().add("chat-date-line");
        right.getStyleClass().add("chat-date-line");
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox divider = new HBox(12, left, label, right);
        divider.setAlignment(Pos.CENTER);
        divider.getStyleClass().add("chat-date-divider");
        container.getChildren().add(divider);
        renderedDate = date;
        previousMessage = null;
        activeGroup = null;
    }

    private boolean continuesGroup(ChatMessageModel message) {
        if (previousMessage == null || activeGroup == null || previousMessage.isSystem()
                || previousMessage.isFileOffer() || previousMessage.isMine() != message.isMine()
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
        renderDate(msg);
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
            previousMessage = null;
            activeGroup = null;
            return;
        }

        boolean grouped = continuesGroup(msg);
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

            if (!grouped) {
                activeGroup = new VBox(4);
                activeGroup.setAlignment(Pos.TOP_RIGHT);
                activeGroup.setMaxWidth(480);
                HBox row = new HBox(activeGroup);
                row.setAlignment(Pos.CENTER_RIGHT);
                container.getChildren().add(row);
            }
            activeGroup.getChildren().add(messageBubbleBox);
        } else {
            contentLbl.getStyleClass().add("bubble-received");
            contentLbl.getStyleClass().add("bubble-received-text");

            messageBubbleBox.setAlignment(Pos.TOP_LEFT);
            messageBubbleBox.getChildren().addAll(contentLbl, timeLbl);

            if (!grouped) {
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
                Tooltip.install(smallAvatar, new Tooltip(msg.getSender()));

                activeGroup = new VBox(4);
                activeGroup.setAlignment(Pos.TOP_LEFT);
                activeGroup.setMaxWidth(480);
                row.getChildren().addAll(smallAvatar, activeGroup);
                container.getChildren().add(row);
            }
            activeGroup.getChildren().add(messageBubbleBox);
        }
        previousMessage = msg;
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
        fileInfo.setMinWidth(0);
        HBox.setHgrow(fileInfo, Priority.ALWAYS);
        Label nameLbl = new Label(offer.getFileName());
        nameLbl.getStyleClass().add("file-card-title");
        nameLbl.setMinWidth(0);
        nameLbl.setMaxWidth(Double.MAX_VALUE);
        Tooltip fileTooltip = new Tooltip();
        fileTooltip.textProperty().bind(Bindings.when(offer.localPathProperty().isNotEmpty())
                .then(offer.getFileName() + "\nBấm để mở file").otherwise(offer.getFileName()));
        nameLbl.setTooltip(fileTooltip);
        nameLbl.cursorProperty().bind(Bindings.when(offer.localPathProperty().isNotEmpty())
                .then(Cursor.HAND).otherwise(Cursor.DEFAULT));
        nameLbl.focusTraversableProperty().bind(offer.localPathProperty().isNotEmpty());
        nameLbl.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && !offer.getLocalPath().isBlank()) {
                openDownloadedFile(offer.getLocalPath());
            }
        });
        nameLbl.setOnKeyPressed(event -> {
            if ((event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE)
                    && !offer.getLocalPath().isBlank()) {
                openDownloadedFile(offer.getLocalPath());
                event.consume();
            }
        });
        Label metaLbl = new Label(offer.getFormattedSize());
        metaLbl.getStyleClass().add("file-card-meta");
        fileInfo.getChildren().addAll(nameLbl, metaLbl);
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
            case PENDING -> "Đang chờ người nhận tải xuống";
            case ACCEPTED -> "Đang chuẩn bị tải xuống";
            case TRANSFERRING -> "Đang truyền file";
            case COMPLETED -> offer.isIncoming() ? "Đã tải xuống" : "Đã gửi file";
            case EXPIRED -> "File đã hết hạn";
            case REJECTED -> "File không được nhận";
            case CANCELING -> "Đang hủy và xóa file tải dở";
            case CANCELED -> "Đã hủy truyền file";
            case FAILED -> "Không thể truyền file: " + offer.getStatusText();
        }, offer.statusProperty(), offer.statusTextProperty()));
        statusIcon.setTooltip(statusTooltip);
        statusIcon.accessibleTextProperty().bind(statusTooltip.textProperty());
        statusIcon.visibleProperty().bind(offer.localPathProperty().isEmpty().and(
                offer.statusProperty().isNotEqualTo(FileOfferModel.Status.PENDING)
                        .or(Bindings.createBooleanBinding(() -> !offer.isIncoming(), offer.incomingProperty()))));
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
        cancelButton.setTooltip(new Tooltip("Hủy truyền file"));
        cancelButton.setAccessibleText("Hủy truyền " + offer.getFileName());
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
            downloadButton.setTooltip(new Tooltip("Tải xuống"));
            downloadButton.setAccessibleText("Tải xuống " + offer.getFileName());
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
        folderButton.setTooltip(new Tooltip("Mở thư mục chứa file"));
        folderButton.setAccessibleText("Mở thư mục chứa " + offer.getFileName());
        folderButton.visibleProperty().bind(offer.localPathProperty().isNotEmpty());
        folderButton.setOnAction(event -> openDownloadedFolder(offer.getLocalPath()));
        topRow.getChildren().add(folderButton);

        card.getChildren().addAll(topRow, pb, statistics);

        HBox outer = new HBox(card);
        outer.setAlignment(msg.isMine() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        container.getChildren().add(outer);
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
