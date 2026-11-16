package ui.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class FileOfferModel {

    public enum Status {
        PENDING,
        ACCEPTED,
        REJECTED,
        TRANSFERRING,
        COMPLETED,
        EXPIRED,
        CANCELING,
        CANCELED,
        FAILED
    }

    private final StringProperty offerNumber = new SimpleStringProperty();
    private final StringProperty sender = new SimpleStringProperty();
    private final StringProperty receiver = new SimpleStringProperty();
    private final StringProperty fileName = new SimpleStringProperty();
    private final StringProperty formattedSize = new SimpleStringProperty();
    private final StringProperty checksum = new SimpleStringProperty();
    private final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.PENDING);
    private final StringProperty statusText = new SimpleStringProperty("Pending");
    private final BooleanProperty incoming = new SimpleBooleanProperty(true);
    private final DoubleProperty progress = new SimpleDoubleProperty(0.0);
    private final StringProperty transferStats = new SimpleStringProperty("—/s • Còn lại: —");
    private final StringProperty localPath = new SimpleStringProperty("");
    private final BooleanProperty broadcast = new SimpleBooleanProperty(false);
    private final StringProperty communityId = new SimpleStringProperty("");
    private final long fileSizeInBytes;

    public FileOfferModel(String offerNumber, String sender, String receiver, String fileName, long fileSize, String checksum, boolean incoming) {
        this.offerNumber.set(offerNumber);
        this.sender.set(sender);
        this.receiver.set(receiver);
        this.fileName.set(fileName);
        this.fileSizeInBytes = fileSize;
        this.formattedSize.set(formatFileSize(fileSize));
        this.checksum.set(checksum);
        this.incoming.set(incoming);
        this.statusText.set(incoming ? "Bấm nút tải xuống để lưu file" : "Đang chờ người nhận tải xuống...");
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %siB", bytes / Math.pow(1024, exp), pre);
    }

    public String getOfferNumber() { return offerNumber.get(); }
    public StringProperty offerNumberProperty() { return offerNumber; }

    public String getSender() { return sender.get(); }
    public StringProperty senderProperty() { return sender; }

    public String getReceiver() { return receiver.get(); }
    public StringProperty receiverProperty() { return receiver; }

    public String getFileName() { return fileName.get(); }
    public StringProperty fileNameProperty() { return fileName; }

    public String getFormattedSize() { return formattedSize.get(); }
    public StringProperty formattedSizeProperty() { return formattedSize; }

    public long getFileSizeInBytes() { return fileSizeInBytes; }

    public String getChecksum() { return checksum.get(); }
    public StringProperty checksumProperty() { return checksum; }

    public Status getStatus() { return status.get(); }
    public ObjectProperty<Status> statusProperty() { return status; }
    public void setStatus(Status status) {
        this.status.set(status);
        switch (status) {
            case PENDING -> this.statusText.set(isIncoming() ? "Bấm nút tải xuống để lưu file" : "Đang chờ người nhận tải xuống...");
            case ACCEPTED -> this.statusText.set("Đang chuẩn bị tải xuống...");
            case REJECTED -> this.statusText.set("Rejected");
            case TRANSFERRING -> this.statusText.set("Transferring file...");
            case COMPLETED -> this.statusText.set(isIncoming() ? "Đã lưu file" : "Sent successfully!");
            case EXPIRED -> this.statusText.set("File đã hết hạn");
            case CANCELING -> this.statusText.set("Đang hủy truyền file");
            case CANCELED -> this.statusText.set("Đã hủy truyền file");
            case FAILED -> this.statusText.set("Transfer failed or cancelled");
        }
    }

    public String getStatusText() { return statusText.get(); }
    public StringProperty statusTextProperty() { return statusText; }
    public void setStatusText(String text) { this.statusText.set(text); }

    public boolean isIncoming() { return incoming.get(); }
    public BooleanProperty incomingProperty() { return incoming; }

    public double getProgress() { return progress.get(); }
    public DoubleProperty progressProperty() { return progress; }
    public void setProgress(double value) { this.progress.set(value); }

    public StringProperty transferStatsProperty() { return transferStats; }

    public void updateTransferStats(double bytesPerSecond, long remainingSeconds) {
        String speed = bytesPerSecond > 0 ? formatFileSize((long) bytesPerSecond) + "/s" : "—/s";
        String remaining;
        if (remainingSeconds < 0) remaining = "—";
        else if (remainingSeconds < 60) remaining = remainingSeconds + " giây";
        else if (remainingSeconds < 3600) remaining = remainingSeconds / 60 + " phút " + remainingSeconds % 60 + " giây";
        else remaining = remainingSeconds / 3600 + " giờ " + (remainingSeconds % 3600) / 60 + " phút";
        transferStats.set(speed + " • Còn lại: " + remaining);
    }

    public String getLocalPath() { return localPath.get(); }
    public StringProperty localPathProperty() { return localPath; }
    public void setLocalPath(String path) { this.localPath.set(path); }

    public boolean isBroadcast() { return broadcast.get(); }
    public BooleanProperty broadcastProperty() { return broadcast; }
    public void setBroadcast(boolean broadcast) { this.broadcast.set(broadcast); }

    public String getCommunityId() { return communityId.get(); }
    public StringProperty communityIdProperty() { return communityId; }
    public void setCommunityId(String communityId) { this.communityId.set(communityId); }
}
