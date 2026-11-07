package protocols;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            DataOutputStream writer = new DataOutputStream(byteStream);

            writer.writeByte(op.getCode());
            writer.writeInt(requestId);
            writer.writeInt(data.length);
            writer.write(data);

            writer.flush();

            return byteStream.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Cannot serialize packet", e);
        }
    }
}
