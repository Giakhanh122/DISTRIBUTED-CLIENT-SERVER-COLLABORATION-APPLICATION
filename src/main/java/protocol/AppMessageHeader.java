package protocol;

import java.util.UUID;

public class AppMessageHeader {
  private final UUID msgId;
  private final AppMessageType type;
  private final String senderId;
  private final String receiverId;
  private final long timestamp;
  private final String contentType;

  public AppMessageHeader(
      UUID msgId,
      AppMessageType type,
      String senderId,
      String receiverId,
      long timestamp,
      String contentType) {
    this.msgId = msgId;
    this.type = type;
    this.senderId = senderId;
    this.receiverId = receiverId;
    this.timestamp = timestamp;
    this.contentType = contentType;
  }

  public UUID getMsgId() {
    return msgId;
  }

  public AppMessageType getType() {
    return type;
  }

  public String getSenderId() {
    return senderId;
  }

  public String getReceiverId() {
    return receiverId;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public String getContentType() {
    return contentType;
  }
}
