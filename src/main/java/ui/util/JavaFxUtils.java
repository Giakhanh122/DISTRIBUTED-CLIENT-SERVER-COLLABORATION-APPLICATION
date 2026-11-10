package ui.util;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.DialogPane;
import javafx.scene.paint.Color;

public final class JavaFxUtils {

    private static final String[] AVATAR_COLORS = {
        "#5865F2", // Discord Blurple
        "#57F287", // Discord Green
        "#FEE75C", // Discord Yellow
        "#EB459E", // Discord Fuchsia
        "#ED4245", // Discord Red
        "#1ABC9C", // Teal
        "#3498DB", // Sky Blue
        "#9B59B6", // Purple
        "#E67E22", // Orange
        "#6C8CFF"  // Primary Accent
    };

    private JavaFxUtils() {}

    public static void runOnFxThread(Runnable action) {
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (IllegalStateException e) {
            // Fallback when JavaFX toolkit is not running (e.g. headless tests)
            action.run();
        }
    }

    public static String getAvatarColor(String username) {
        if (username == null || username.isEmpty()) {
            return AVATAR_COLORS[0];
        }
        int hash = Math.abs(username.hashCode());
        return AVATAR_COLORS[hash % AVATAR_COLORS.length];
    }

    public static void showErrorAlert(String title, String message) {
        runOnFxThread(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            styleDialog(alert.getDialogPane());
            alert.showAndWait();
        });
    }

    public static void showInfoAlert(String title, String message) {
        runOnFxThread(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            styleDialog(alert.getDialogPane());
            alert.showAndWait();
        });
    }

    private static void styleDialog(DialogPane pane) {
        pane.setStyle("-fx-background-color: #181B22; -fx-text-fill: #F1F3F8;");
    }
}
