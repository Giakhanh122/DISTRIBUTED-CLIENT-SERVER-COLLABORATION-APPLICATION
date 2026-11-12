package ui.controller;

import java.io.IOException;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import ui.adapter.ChatClientAdapter;
import ui.adapter.ClientEventListener;
import ui.util.IconUtils;
import ui.util.WindowDragHelper;

public class LoginController implements ClientEventListener {

    @FXML private HBox titleBar;
    @FXML private Button minimizeBtn;
    @FXML private Button maximizeBtn;
    @FXML private Button closeBtn;

    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private Button discoverBtn;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button loginBtn;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Label errorLabel;
    @FXML private Label statusLabel;

    private final ChatClientAdapter adapter = ChatClientAdapter.getInstance();

    @FXML
    public void initialize() {
        adapter.addListener(this);
        WindowDragHelper.makeDraggable(titleBar);
        discoverBtn.setGraphic(IconUtils.createIconNode(IconUtils.REFRESH, 13, "#71717A"));
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
    private void handleDiscover() {
        showStatus("Searching for server via UDP broadcast...");
        hideError();
        discoverBtn.setDisable(true);

        adapter.discoverServer(info -> {
            discoverBtn.setDisable(false);
            if (info != null) {
                hostField.setText(info.address());
                portField.setText(Integer.toString(info.port()));
                showStatus("Found server at " + info.address() + ":" + info.port());
            } else {
                showError("No server discovered. Ensure server is running or specify host manually.");
                hideStatus();
            }
        });
    }

    @FXML
    private void handleLogin() {
        String host = hostField.getText().trim();
        String portText = portField.getText().trim();
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (host.isEmpty()) {
            showError("Server host cannot be empty");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) {
                showError("Port must be between 1 and 65535");
                return;
            }
        } catch (NumberFormatException e) {
            showError("Invalid port number");
            return;
        }

        if (username.isEmpty()) {
            showError("Username cannot be empty");
            return;
        }

        if (password.isEmpty()) {
            showError("Password cannot be empty");
            return;
        }

        setLoading(true);
        hideError();
        showStatus("Connecting to server...");

        adapter.login(host, port, username, password);
    }

    private void setLoading(boolean loading) {
        loginBtn.setDisable(loading);
        discoverBtn.setDisable(loading);
        hostField.setDisable(loading);
        portField.setDisable(loading);
        usernameField.setDisable(loading);
        passwordField.setDisable(loading);
        loadingIndicator.setVisible(loading);
        loadingIndicator.setManaged(loading);
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
        hideStatus();
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private void showStatus(String msg) {
        statusLabel.setText(msg);
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
    }

    private void hideStatus() {
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
    }

    @Override
    public void onLoginSuccess(String username) {
        setLoading(false);
        hideError();
        hideStatus();

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/chat.fxml"));
            Parent root = loader.load();

            Stage stage = (Stage) loginBtn.getScene().getWindow();
            Scene scene = new Scene(root, 1060, 700);
            stage.setTitle("DL-Chat - " + username);
            stage.setScene(scene);
            stage.setMinWidth(850);
            stage.setMinHeight(580);
            stage.centerOnScreen();

            adapter.removeListener(this);
        } catch (IOException e) {
            showError("Failed to open chat screen: " + e.getMessage());
        }
    }

    @Override
    public void onLoginFailed(String reason) {
        setLoading(false);
        showError(reason != null ? reason : "Login failed. Check your credentials.");
    }

    @Override public void onConnectionClosed() { setLoading(false); }
    @Override public void onSystemNotification(String message, boolean isError) {
        if (isError && loadingIndicator.isVisible()) {
            showError(message);
        }
    }
}
