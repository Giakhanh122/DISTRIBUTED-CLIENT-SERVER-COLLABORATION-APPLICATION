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

    public State state() {
        return state;
    }

    public long bytesTransferred() {
        return bytesTransferred;
    }

    public int progressPercent() {
        long total = meta.fileSize();
        if (total <= 0) return 0;
        return (int) (bytesTransferred * 100 / total);
    }

    public void accept() {
        requireState(State.OFFERED, "accept");
        this.state = State.ACCEPTED;
    }

    public void reject() {
        requireState(State.OFFERED, "reject");
        this.state = State.REJECTED;
    }

    public void startTransfer() {
        requireState(State.ACCEPTED, "startTransfer");
        this.state = State.TRANSFERRING;
    }

    public void addBytesTransferred(long chunkSize) {
        requireState(State.TRANSFERRING, "addBytesTransferred");
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0");
        }
        this.bytesTransferred += chunkSize;
        if (bytesTransferred > meta.fileSize()) {
            throw new IllegalStateException(
                    "Bytes transferred (" + bytesTransferred
                    + ") exceeds file size (" + meta.fileSize() + ")");
        }
    }

    public void complete() {
        requireState(State.TRANSFERRING, "complete");
        this.state = State.COMPLETED;
    }

    public void fail() {
        if (isTerminal()) {
            throw new IllegalStateException(
                    "Cannot fail: session already in terminal state " + state);
        }
        this.state = State.FAILED;
    }

    public void cancel() {
        if (isTerminal()) {
            throw new IllegalStateException(
                    "Cannot cancel: session already in terminal state " + state);
        }
        this.state = State.CANCELED;
    }

    public boolean isTerminal() {
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
    public String toString() {
        return "FileTransferSession{"
                + "transferId='" + transferId() + '\''
                + ", state=" + state
                + ", progress=" + progressPercent() + "%"
                + ", " + bytesTransferred + "/" + meta.fileSize() + " bytes"
                + '}';
    }
}
