package client.transfer;

import client.ClientEvents;
import transfer.FileReceiver;
import transfer.FileSender;

final class FileProgress implements FileSender.ProgressListener, FileReceiver.ProgressListener {
    private static final long UPDATE_NANOS = 250_000_000L;
    private final ClientEvents events;
    private final String id;
    private long lastUpdate;
    private long lastBytes;
    private double speed;

    FileProgress(ClientEvents events, String id) {
        this.events = events;
        this.id = id;
    }

    @Override
    public void onProgress(long transferred, long total, int percent) {
        long now = System.nanoTime();
        if (lastUpdate != 0 && now - lastUpdate < UPDATE_NANOS && transferred < total) return;
        if (lastUpdate != 0 && now > lastUpdate) {
            double sample = (transferred - lastBytes) * 1_000_000_000.0 / (now - lastUpdate);
            speed = speed == 0 ? sample : speed * 0.7 + sample * 0.3;
        }
        lastUpdate = now;
        lastBytes = transferred;
        long remaining = speed > 0 ? (long) Math.ceil(Math.max(0, total - transferred) / speed) : -1;
        events.onFileProgress(id, transferred, total, speed, remaining);
    }
}
