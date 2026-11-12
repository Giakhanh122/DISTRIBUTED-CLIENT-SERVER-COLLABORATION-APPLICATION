package ui.util;

import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.stage.Screen;
import javafx.stage.Stage;

public final class WindowDragHelper {

    private static double xOffset = 0;
    private static double yOffset = 0;

    private static double prevX = 0;
    private static double prevY = 0;
    private static double prevWidth = 0;
    private static double prevHeight = 0;
    private static boolean isCustomMaximized = false;

    private WindowDragHelper() {}

    public static void makeDraggable(Node titleBar) {
        titleBar.setOnMousePressed(event -> {
            Stage stage = (Stage) titleBar.getScene().getWindow();
            if (isCustomMaximized) return;
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });

        titleBar.setOnMouseDragged(event -> {
            Stage stage = (Stage) titleBar.getScene().getWindow();
            if (isCustomMaximized) {
                toggleMaximize(stage);
                xOffset = stage.getWidth() / 2;
                yOffset = 19;
            }
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        });

        titleBar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                Stage stage = (Stage) titleBar.getScene().getWindow();
                toggleMaximize(stage);
            }
        });
    }

    public static void toggleMaximize(Stage stage) {
        if (!isCustomMaximized) {
            prevX = stage.getX();
            prevY = stage.getY();
            prevWidth = stage.getWidth();
            prevHeight = stage.getHeight();

            Screen screen = Screen.getScreensForRectangle(prevX, prevY, Math.max(10, prevWidth), Math.max(10, prevHeight))
                    .stream()
                    .findFirst()
                    .orElse(Screen.getPrimary());

            Rectangle2D bounds = screen.getVisualBounds();
            stage.setX(bounds.getMinX());
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());
            isCustomMaximized = true;
        } else {
            stage.setX(prevX);
            stage.setY(prevY);
            stage.setWidth(prevWidth);
            stage.setHeight(prevHeight);
            isCustomMaximized = false;
        }
    }

    public static boolean isMaximized() {
        return isCustomMaximized;
    }
}
