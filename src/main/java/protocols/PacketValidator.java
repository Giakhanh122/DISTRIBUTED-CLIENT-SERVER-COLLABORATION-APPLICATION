package protocols;

import java.io.IOException;

public final class PacketValidator {
    private PacketValidator() {}

    public static Packet validateResponse(Packet packet, OpCode expectedOp, int requestId)
            throws IOException {
        if (packet == null) {
            throw new IOException("Missing " + expectedOp);
        }
        if (packet.op() != expectedOp) {
            throw new IOException("Expected " + expectedOp + ", got " + packet.op());
        }
        if (packet.requestId() != requestId) {
            throw new IOException("Expected request ID " + requestId + ", got " + packet.requestId());
        }
        return packet;
    }

    public static Packet validateRequest(Packet packet, OpCode expectedOp,
                                        int minPayloadLength, int maxPayloadLength) throws IOException {
        if (packet == null || packet.op() != expectedOp || packet.requestId() <= 0) {
            throw new IOException("Invalid or missing " + expectedOp);
        }
        int length = packet.payloadLength();
        if (length < minPayloadLength || length > maxPayloadLength) {
            throw new IOException("Invalid " + expectedOp + " payload length: " + length);
        }
        return packet;
    }
}
