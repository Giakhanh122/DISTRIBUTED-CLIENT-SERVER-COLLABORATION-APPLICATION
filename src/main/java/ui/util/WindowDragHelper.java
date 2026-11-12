package ui.util;

import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** Window bounds belong to each stage, including modal dialogs. */
public final class WindowDragHelper {
    private static final Object STATE_KEY = new Object();

    private static final class WindowState {
        double xOffset;
        double yOffset;
        Rectangle2D restoredBounds;
    }

    private WindowDragHelper() {}

    private static WindowState state(Stage stage) {
        return (WindowState) stage.getProperties().computeIfAbsent(STATE_KEY, key -> new WindowState());
    }

    public static void makeDraggable(Node titleBar) {
        titleBar.setOnMousePressed(event -> {
            Stage stage = (Stage) titleBar.getScene().getWindow();
            WindowState state = state(stage);
            state.xOffset = event.getSceneX();
            state.yOffset = event.getSceneY();
        });
        titleBar.setOnMouseDragged(event -> {
            Stage stage = (Stage) titleBar.getScene().getWindow();
            WindowState state = state(stage);
            if (state.restoredBounds != null) {
                toggleMaximize(stage);
                state.xOffset = stage.getWidth() / 2;
                state.yOffset = 19;
            }
            stage.setX(event.getScreenX() - state.xOffset);
            stage.setY(event.getScreenY() - state.yOffset);
        });
        titleBar.setOnMouseClicked(event -> {
            Stage stage = (Stage) titleBar.getScene().getWindow();
            if (event.getClickCount() == 2 && stage.isResizable()) toggleMaximize(stage);
        });
    }

    public static void toggleMaximize(Stage stage) {
        WindowState state = state(stage);
        if (state.restoredBounds == null) {
            state.restoredBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            Rectangle2D previous = state.restoredBounds;
            Screen screen = Screen.getScreensForRectangle(previous).stream().findFirst().orElse(Screen.getPrimary());
            setBounds(stage, screen.getVisualBounds());
        } else {
            setBounds(stage, state.restoredBounds);
            state.restoredBounds = null;
        }
    }

    private static void setBounds(Stage stage, Rectangle2D bounds) {
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
    }
}
