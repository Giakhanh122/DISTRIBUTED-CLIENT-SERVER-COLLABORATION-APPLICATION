package transfer;

import protocols.FileTransferMeta;

public class FileTransferSession {
    public enum State {
        OFFERED, ACCEPTED, REJECTED, TRANSFERRING, COMPLETED, FAILED, CANCELED
    }

    private final FileTransferMeta meta;
    private State state;
    private long bytesTransferred;

    public FileTransferSession(FileTransferMeta meta) {
        this.meta = meta;
        this.state = State.OFFERED;
        this.bytesTransferred = 0;
    }

    public FileTransferMeta meta() { return meta; }
    public String transferId() { return meta.transferId(); }
    public State state() { return state; }
    public long bytesTransferred() { return bytesTransferred; }

    public void accept() { this.state = State.ACCEPTED; }
    public void reject() { this.state = State.REJECTED; }
    public void startTransfer() { this.state = State.TRANSFERRING; }
    public void complete() { this.state = State.COMPLETED; }
    public void fail() { this.state = State.FAILED; }
    public void cancel() { this.state = State.CANCELED; }

    public void addBytesTransferred(long bytes) {
        this.bytesTransferred += bytes;
    }

    public boolean isTerminal() {
        return state == State.COMPLETED || state == State.FAILED || state == State.CANCELED || state == State.REJECTED;
    }
}
