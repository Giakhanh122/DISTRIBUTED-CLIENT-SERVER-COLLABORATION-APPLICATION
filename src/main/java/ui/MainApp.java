package ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import ui.adapter.ChatClientAdapter;

public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/login.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 860, 600);
        primaryStage.initStyle(StageStyle.UNDECORATED);
        primaryStage.setTitle("DL-Chat");

        try {
            primaryStage.getIcons().addAll(
                new Image(getClass().getResourceAsStream("/assets/DL-Chat-icon-16.png")),
                new Image(getClass().getResourceAsStream("/assets/DL-Chat-icon-32.png")),
                new Image(getClass().getResourceAsStream("/assets/DL-Chat-icon-64.png")),
                new Image(getClass().getResourceAsStream("/assets/DL-Chat-icon-128.png")),
                new Image(getClass().getResourceAsStream("/assets/DL-Chat-icon-256.png"))
            );
        } catch (Exception ignored) {}

        primaryStage.setScene(scene);
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(540);

        primaryStage.setOnCloseRequest(e -> {
            ChatClientAdapter.getInstance().logout();
            Platform.exit();
            System.exit(0);
        });

        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
