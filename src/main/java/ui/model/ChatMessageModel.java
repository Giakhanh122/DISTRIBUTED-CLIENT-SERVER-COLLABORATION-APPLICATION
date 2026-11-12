package ui.model;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class ChatMessageModel {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public enum Type {
        TEXT,
        FILE_OFFER,
        SYSTEM
    }

    private final Type type;
    private final String sender;
    private final String content;
    private final String timestamp;
    private final boolean mine;
    private FileOfferModel fileOffer;

    public ChatMessageModel(String sender, String content, boolean mine) {
        this(Type.TEXT, sender, content, LocalTime.now().format(TIME_FORMATTER), mine, null);
    }

    public ChatMessageModel(Type type, String sender, String content, String timestamp, boolean mine, FileOfferModel fileOffer) {
        this.type = type;
        this.sender = sender;
        this.content = content;
        this.timestamp = timestamp != null ? timestamp : LocalTime.now().format(TIME_FORMATTER);
        this.mine = mine;
        this.fileOffer = fileOffer;
    }

    public static ChatMessageModel system(String message) {
        return new ChatMessageModel(Type.SYSTEM, "System", message, LocalTime.now().format(TIME_FORMATTER), false, null);
    }

    public static ChatMessageModel file(String sender, FileOfferModel offer, boolean mine) {
        return new ChatMessageModel(Type.FILE_OFFER, sender, offer.getFileName(), LocalTime.now().format(TIME_FORMATTER), mine, offer);
    }

    public Type getType() { return type; }
    public String getSender() { return sender; }
    public String getContent() { return content; }
    public String getTimestamp() { return timestamp; }
    public boolean isMine() { return mine; }
    public boolean isSystem() { return type == Type.SYSTEM; }
    public boolean isFileOffer() { return type == Type.FILE_OFFER; }
    public FileOfferModel getFileOffer() { return fileOffer; }
    public void setFileOffer(FileOfferModel fileOffer) { this.fileOffer = fileOffer; }
}
