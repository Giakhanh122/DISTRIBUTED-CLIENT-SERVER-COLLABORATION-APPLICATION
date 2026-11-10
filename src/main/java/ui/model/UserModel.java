package ui.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.Objects;

public class UserModel {
    private final StringProperty username = new SimpleStringProperty();
    private final BooleanProperty online = new SimpleBooleanProperty(true);
    private final IntegerProperty unreadCount = new SimpleIntegerProperty(0);
    private final StringProperty statusMessage = new SimpleStringProperty("Online");

    public UserModel(String username) {
        this.username.set(username);
    }

    public UserModel(String username, boolean online) {
        this.username.set(username);
        this.online.set(online);
        this.statusMessage.set(online ? "Online" : "Offline");
    }

    public String getUsername() {
        return username.get();
    }

    public StringProperty usernameProperty() {
        return username;
    }

    public void setUsername(String username) {
        this.username.set(username);
    }

    public boolean isOnline() {
        return online.get();
    }

    public BooleanProperty onlineProperty() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online.set(online);
        this.statusMessage.set(online ? "Online" : "Offline");
    }

    public int getUnreadCount() {
        return unreadCount.get();
    }

    public IntegerProperty unreadCountProperty() {
        return unreadCount;
    }

    public void setUnreadCount(int unreadCount) {
        this.unreadCount.set(unreadCount);
    }

    public void incrementUnread() {
        this.unreadCount.set(this.unreadCount.get() + 1);
    }

    public void clearUnread() {
        this.unreadCount.set(0);
    }

    public String getStatusMessage() {
        return statusMessage.get();
    }

    public StringProperty statusMessageProperty() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage.set(statusMessage);
    }

    public String getInitials() {
        String name = getUsername();
        if (name == null || name.isBlank()) return "?";
        return name.length() <= 2 ? name.toUpperCase() : name.substring(0, 2).toUpperCase();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserModel userModel = (UserModel) o;
        return Objects.equals(getUsername(), userModel.getUsername());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getUsername());
    }

    @Override
    public String toString() {
        return getUsername();
    }
}
