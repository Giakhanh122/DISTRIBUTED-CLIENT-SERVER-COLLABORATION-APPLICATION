package transfer;

import protocols.FileTransferMeta;

public class FileTransferSession {

    public enum State {
        OFFERED,
        ACCEPTED,
        REJECTED,
        TRANSFERRING,
        COMPLETED,
        FAILED,
        CANCELED
    }

    private final FileTransferMeta meta;
    private State state;
    private long bytesTransferred;

    public FileTransferSession(FileTransferMeta meta) {
        if (meta == null) {
            throw new IllegalArgumentException("meta must not be null");
        }
        this.meta = meta;
        this.state = State.OFFERED;
        this.bytesTransferred = 0;
    }

    public FileTransferMeta meta() {
        return meta;
    }

    public String transferId() {
        return meta.transferId();
    }

    public synchronized State state() {
        return state;
    }

    public synchronized long bytesTransferred() {
        return bytesTransferred;
    }

    public synchronized int progressPercent() {
        long total = meta.fileSize();
        if (total <= 0) return state == State.COMPLETED ? 100 : 0;
        return (int) (bytesTransferred * 100 / total);
    }

    public synchronized void accept() {
        requireState(State.OFFERED, "accept");
        this.state = State.ACCEPTED;
    }

    public synchronized void reject() {
        requireState(State.OFFERED, "reject");
        this.state = State.REJECTED;
    }

    public synchronized void startTransfer() {
        requireState(State.ACCEPTED, "startTransfer");
        this.state = State.TRANSFERRING;
    }

    public synchronized void addBytesTransferred(long chunkSize) {
        requireState(State.TRANSFERRING, "addBytesTransferred");
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0");
        }
        if (chunkSize > meta.fileSize() - bytesTransferred) {
            throw new IllegalStateException(
                    "Bytes transferred (" + (bytesTransferred + chunkSize)
                    + ") exceeds file size (" + meta.fileSize() + ")");
        }
        this.bytesTransferred += chunkSize;
    }

    public synchronized void complete() {
        requireState(State.TRANSFERRING, "complete");
        if (bytesTransferred != meta.fileSize()) {
            throw new IllegalStateException("Cannot complete an incomplete file transfer");
        }
        this.state = State.COMPLETED;
    }

    public synchronized void fail() {
        if (isTerminal()) {
            return;
        }
        this.state = State.FAILED;
    }

    public synchronized void cancel() {
        if (isTerminal()) {
            return;
        }
        this.state = State.CANCELED;
    }

    public synchronized boolean isTerminal() {
        return state == State.COMPLETED
            || state == State.FAILED
            || state == State.CANCELED
            || state == State.REJECTED;
    }

    private void requireState(State expected, String action) {
        if (state != expected) {
            throw new IllegalStateException(
                    "Cannot " + action + ": expected state " + expected
                    + " but current state is " + state);
        }
    }

    @Override
    public synchronized String toString() {
        return "FileTransferSession{"
                + "transferId='" + transferId() + '\''
                + ", state=" + state
                + ", progress=" + progressPercent() + "%"
                + ", " + bytesTransferred + "/" + meta.fileSize() + " bytes"
                + '}';
    }
}
