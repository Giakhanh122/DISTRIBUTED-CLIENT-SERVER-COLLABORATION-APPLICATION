package ui.util;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

public final class JavaFxUtils {

    private static final String[] AVATAR_COLORS = {
        "#09090B", // Pure Jet Black
        "#D97706", // Dark Amber
        "#B45309", // Deep Gold
        "#27272A", // Zinc Dark
        "#F59E0B", // Bright Amber Yellow
        "#3F3F46", // Charcoal
        "#52525B", // Zinc Slate
        "#78350F"  // Rich Bronze
    };

    private JavaFxUtils() {}

    public static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }

    public static void runOnFxThreadAndWait(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        try {
            task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(false);
            throw new IllegalStateException("Interrupted while waiting for JavaFX", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("JavaFX action failed", e.getCause());
        }
    }

    public static String getAvatarColor(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return AVATAR_COLORS[0];
        }
        return AVATAR_COLORS[Math.floorMod(identifier.hashCode(), AVATAR_COLORS.length)];
    }

    private static Stage createCustomDialogStage(Window owner) {
        Stage dialogStage = new Stage();
        dialogStage.initModality(Modality.APPLICATION_MODAL);
        dialogStage.initStyle(StageStyle.UNDECORATED);
        if (owner != null) {
            dialogStage.initOwner(owner);
        }
        try {
            dialogStage.getIcons().add(new javafx.scene.image.Image(JavaFxUtils.class.getResourceAsStream("/assets/DL-Chat-icon-64.png")));
        } catch (Exception ignored) {}
        return dialogStage;
    }

    private static HBox createDialogTitleBar(Stage stage, String title) {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPrefHeight(38.0);
        bar.setMinHeight(38.0);
        bar.setMaxHeight(38.0);
        bar.setStyle("-fx-background-color: #09090B; -fx-padding: 0 0 0 14px; -fx-border-color: #27272A; -fx-border-width: 0 0 1 0;");

        try {
            javafx.scene.image.ImageView icon = new javafx.scene.image.ImageView(
                new javafx.scene.image.Image(JavaFxUtils.class.getResourceAsStream("/assets/DL-Chat-icon-32.png"))
            );
            icon.setFitWidth(16.0);
            icon.setFitHeight(16.0);
            icon.setPreserveRatio(true);
            bar.getChildren().add(icon);
        } catch (Exception ignored) {}

        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 13px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().add("window-btn-close");
        closeBtn.setOnAction(e -> stage.close());

        bar.getChildren().addAll(titleLbl, spacer, closeBtn);
        WindowDragHelper.makeDraggable(bar);
        return bar;
    }

    private static VBox createDialogRoot(HBox titleBar, Node content, Node buttonBar) {
        VBox root = new VBox();
        root.getStyleClass().add("window-root");
        root.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #27272A; -fx-border-width: 1px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 18, 0, 0, 4);");

        VBox body = new VBox(16);
        body.setPadding(new Insets(20, 24, 20, 24));
        body.getChildren().addAll(content, buttonBar);

        root.getChildren().addAll(titleBar, body);
        return root;
    }

    private static void centerOnOwner(Stage stage, Window owner) {
        stage.setOnShown(e -> {
            if (owner != null && owner.isShowing()) {
                stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2.0);
                stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2.0);
            } else {
                stage.centerOnScreen();
            }
        });
    }

    private static void showDialog(Stage stage, Window owner, String title, Node content, Node actions) {
        Scene scene = new Scene(createDialogRoot(createDialogTitleBar(stage, title), content, actions));
        var css = JavaFxUtils.class.getResource("/css/dl-chat-theme.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setScene(scene);
        stage.setResizable(false);
        centerOnOwner(stage, owner);
        stage.showAndWait();
    }

    public static void showErrorAlert(Window owner, String title, String message) {
        showMessageDialog(owner, title, message);
    }

    private static void showMessageDialog(Window owner, String title, String message) {
        runOnFxThread(() -> {
            Stage stage = createCustomDialogStage(owner);

            VBox content = new VBox(8);
            Label msgLbl = new Label(message);
            msgLbl.setWrapText(true);
            msgLbl.setPrefWidth(340);
            msgLbl.setStyle("-fx-text-fill: #18181B; -fx-font-size: 13px;");
            content.getChildren().add(msgLbl);

            HBox actions = new HBox(10);
            actions.setAlignment(Pos.CENTER_RIGHT);

            Button okBtn = new Button("OK");
            okBtn.getStyleClass().add("btn-primary");
            okBtn.setDefaultButton(true);
            okBtn.setOnAction(e -> stage.close());
            actions.getChildren().add(okBtn);

            showDialog(stage, owner, title, content, actions);
        });
    }

    public static void showErrorAlert(String title, String message) {
        showErrorAlert(null, title, message);
    }

    public static void showInfoAlert(Window owner, String title, String message) {
        showMessageDialog(owner, title, message);
    }

    public static void showInfoAlert(String title, String message) {
        showInfoAlert(null, title, message);
    }

    public static boolean showConfirmDialog(Window owner, String title, String message) {
        final boolean[] result = new boolean[1];
        runOnFxThreadAndWait(() -> {
            Stage stage = createCustomDialogStage(owner);

            VBox content = new VBox(8);
            Label msgLbl = new Label(message);
            msgLbl.setWrapText(true);
            msgLbl.setPrefWidth(320);
            msgLbl.setStyle("-fx-text-fill: #18181B; -fx-font-size: 13px;");
            content.getChildren().add(msgLbl);

            HBox actions = new HBox(10);
            actions.setAlignment(Pos.CENTER_RIGHT);

            Button noBtn = new Button("No");
            noBtn.getStyleClass().add("btn-secondary");
            noBtn.setCancelButton(true);
            noBtn.setOnAction(e -> {
                result[0] = false;
                stage.close();
            });

            Button yesBtn = new Button("Yes");
            yesBtn.getStyleClass().add("btn-primary");
            yesBtn.setDefaultButton(true);
            yesBtn.setOnAction(e -> {
                result[0] = true;
                stage.close();
            });

            actions.getChildren().addAll(noBtn, yesBtn);

            showDialog(stage, owner, title, content, actions);
        });

        return result[0];
    }

    public static Optional<String> showInputDialog(Window owner, String title, String promptText, String defaultValue) {
        final String[] result = new String[1];
        runOnFxThreadAndWait(() -> {
            Stage stage = createCustomDialogStage(owner);

            VBox content = new VBox(8);
            Label promptLbl = new Label(promptText != null ? promptText : "Enter value:");
            promptLbl.setStyle("-fx-text-fill: #18181B; -fx-font-size: 13px; -fx-font-weight: bold;");

            TextField inputField = new TextField(defaultValue != null ? defaultValue : "");
            inputField.getStyleClass().add("text-input");
            inputField.setPrefWidth(340);
            content.getChildren().addAll(promptLbl, inputField);

            HBox actions = new HBox(10);
            actions.setAlignment(Pos.CENTER_RIGHT);

            Button cancelBtn = new Button("Cancel");
            cancelBtn.getStyleClass().add("btn-secondary");
            cancelBtn.setCancelButton(true);
            cancelBtn.setOnAction(e -> stage.close());

            Button okBtn = new Button("OK");
            okBtn.getStyleClass().add("btn-primary");
            okBtn.setDefaultButton(true);
            okBtn.setOnAction(e -> {
                result[0] = inputField.getText();
                stage.close();
            });

            inputField.setOnAction(e -> okBtn.fire());

            actions.getChildren().addAll(cancelBtn, okBtn);

            Platform.runLater(inputField::requestFocus);
            showDialog(stage, owner, title, content, actions);
        });

        return Optional.ofNullable(result[0]);
    }
}
