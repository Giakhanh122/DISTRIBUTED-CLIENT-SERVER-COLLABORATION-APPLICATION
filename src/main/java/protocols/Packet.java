package protocols;

import java.util.Objects;

public final class Packet {
    private final OpCode op;
    private final int requestId;
    private final byte[] data;

    public Packet(OpCode op, int requestId, byte[] data) {
        this.op = Objects.requireNonNull(op, "op");
        this.requestId = requestId;
        Objects.requireNonNull(data, "data");
        this.data = data.clone();
    }

    public OpCode op() {
        return op;
    }

    public int requestId() {
        return requestId;
    }

    public int payloadLength() {
        return data.length;
    }

    public byte[] data() {
        return data.clone();
    }
}
