package protocol;

public class AppMessage {
  private final AppMessageHeader header;
  private final byte[] data;

  public AppMessage(AppMessageHeader header_, byte[] data_) {
    header = header_;
    this.data = data_;
  }

  AppMessageHeader getHeader() {
    return this.header;
  }

  byte[] getData() {
    return this.data;
  }
}
