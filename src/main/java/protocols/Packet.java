package protocols;

import java.nio.charset.StandardCharsets;
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

    public static Packet text(OpCode op, int requestId, String text) {
        return new Packet(op, requestId, text.getBytes(StandardCharsets.UTF_8));
    }

    public String text() {
        return new String(data, StandardCharsets.UTF_8);
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
