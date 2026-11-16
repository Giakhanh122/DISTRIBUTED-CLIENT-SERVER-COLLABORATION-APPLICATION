package ui.model;

import java.time.LocalDateTime;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import java.time.format.DateTimeFormatter;

public class ChatMessageModel {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public enum Type {
        TEXT,
        FILE_OFFER,
        SYSTEM
    }

    private final StringProperty sendStatus = new SimpleStringProperty("");
    private final StringProperty sendStatusDetail = new SimpleStringProperty("");
    private enum SendState { NONE, SENDING, WAITING, UNCONFIRMED, SENT, DELIVERED, FAILED }
    private SendState sendState = SendState.NONE;
    private String messageId;
    public StringProperty sendStatusProperty() { return sendStatus; }
    public StringProperty sendStatusDetailProperty() { return sendStatusDetail; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String id) { messageId = id; }
    public void sending() {
        sendState = SendState.SENDING;
        sendStatus.set("Sending...");
        sendStatusDetail.set("Sending message.");
    }
    public void sent(String error) {
        if (sendState == SendState.DELIVERED) return;
        if (error != null) {
            sendState = SendState.FAILED;
            sendStatus.set("Send failed: " + error);
            sendStatusDetail.set(error);
        } else if (sendState != SendState.UNCONFIRMED) {
            sendState = SendState.WAITING;
            sendStatus.set("Sent");
            sendStatusDetail.set("Sent over the connection. Waiting for confirmation from the recipient.");
        }
    }
    public void broadcastSent(String error) {
        if (error != null) { sent(error); return; }
        sendState = SendState.SENT;
        sendStatus.set("Sent");
        sendStatusDetail.set("Sent to the server for delivery to online community members. Delivery and reading have not been confirmed.");
    }
    public void globalSent(String error) {
        if (error != null) { sent(error); return; }
        sendState = SendState.SENT;
        sendStatus.set("Sent");
        sendStatusDetail.set("Sent to the server for delivery to online users. Delivery and reading have not been confirmed.");
    }
    public void unconfirmed() {
        if (sendState == SendState.DELIVERED || sendState == SendState.FAILED) return;
        sendState = SendState.UNCONFIRMED;
        sendStatus.set("Unconfirmed");
        sendStatusDetail.set("No delivery confirmation received. The message may have arrived.");
    }
    public void delivered() {
        sendState = SendState.DELIVERED;
        sendStatus.set("Sent");
        sendStatusDetail.set("The recipient confirmed delivery. Reading has not been confirmed.");
    }

    private final Type type;
    private final String sender;
    private final String content;
    private final String timestamp;
    private final LocalDateTime createdAt;
    private final boolean mine;
    private final FileOfferModel fileOffer;

    public ChatMessageModel(String sender, String content, boolean mine) {
        this(Type.TEXT, sender, content, null, mine, null);
    }

    public ChatMessageModel(Type type, String sender, String content, String timestamp, boolean mine, FileOfferModel fileOffer) {
        this.type = type;
        this.sender = sender;
        this.content = content;
        this.createdAt = LocalDateTime.now();
        this.timestamp = timestamp != null ? timestamp : createdAt.format(TIME_FORMATTER);
        this.mine = mine;
        this.fileOffer = fileOffer;
    }

    public static ChatMessageModel system(String message) {
        return new ChatMessageModel(Type.SYSTEM, "System", message, null, false, null);
    }

    public static ChatMessageModel file(String sender, FileOfferModel offer, boolean mine) {
        return new ChatMessageModel(Type.FILE_OFFER, sender, offer.getFileName(), null, mine, offer);
    }

    public Type getType() { return type; }
    public String getSender() { return sender; }
    public String getContent() { return content; }
    public String getTimestamp() { return timestamp; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public boolean isMine() { return mine; }
    public boolean isSystem() { return type == Type.SYSTEM; }
    public boolean isFileOffer() { return type == Type.FILE_OFFER; }
    public FileOfferModel getFileOffer() { return fileOffer; }
}
