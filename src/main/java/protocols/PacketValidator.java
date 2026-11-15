package protocols;

import java.io.IOException;

public final class PacketValidator {
    private PacketValidator() {}

    static void validatePacket(Packet packet, long sequence) throws IOException {
        if (sequence <= 0) {
            throw new IOException("Sequence must be positive");
        }
        if (MessageType.fromWire(packet.op(), packet.string("type")) != packet.type()) {
            throw new IOException("Packet type does not match opcode group");
        }
        validateBody(packet);
    }

    private static void validateBody(Packet packet) throws IOException {
        if (packet.type().requiresStatus() && packet.status().isBlank()) {
            throw new IOException("Empty status for " + packet.type());
        }
        if (packet.type() == SystemType.ERROR) {
            if (packet.string("code").isBlank()) {
                throw new IOException("Empty system error code");
            }
            if (packet.string("reason").isBlank()) {
                throw new IOException("Empty system error reason");
            }
            packet.bool("fatal");
        }
        if (!packet.type().requiresReplyTo()) return;
        long replyTo = packet.replyTo();
        boolean earlyFileFailure = packet.type() == FileType.RESULT && packet.status().equals("FAIL");
        if (replyTo < 0 || (replyTo == 0 && !earlyFileFailure)) {
            throw new IOException("Invalid replyTo for " + packet.op() + "/" + packet.type());
        }
    }

    public static Packet validateResponse(Packet packet, MessageType expectedType) throws IOException {
        if (packet == null) throw new IOException("Missing " + expectedType);
        if (packet.op() != expectedType.opcode() || packet.type() != expectedType) {
            throw new IOException("Expected " + expectedType.opcode() + "/" + expectedType + ", got "
                    + packet.op() + "/" + packet.type());
        }
        return packet;
    }

    public static Packet validateResponse(Packet packet, MessageType expectedType, long requestSequence) throws IOException {
        validateResponse(packet, expectedType);
        if (packet.replyTo() != requestSequence) {
            throw new IOException("Expected replyTo " + requestSequence + ", got " + packet.replyTo());
        }
        return packet;
    }

    public static void requireSuccess(Packet response) throws IOException {
        String status = response.status();
        if (!status.equals("OK")) {
            throw new IOException(response.op() + "/" + response.type() + " failed: " + status);
        }
    }

    public static Packet validateRequest(Packet packet, MessageType expectedType,
                                        int minBodyLength, int maxBodyLength) throws IOException {
        if (packet == null || packet.op() != expectedType.opcode() || packet.type() != expectedType) {
            throw new IOException("Invalid or missing " + expectedType);
        }
        if (minBodyLength == 0 && maxBodyLength == 0) {
            if (packet.jsonBody().size() != 1) throw new IOException("Expected only type for " + expectedType);
            return packet;
        }
        int length = packet.bodyLength();
        if (length < minBodyLength || length > maxBodyLength) {
            throw new IOException("Invalid " + expectedType + " body length: " + length);
        }
        return packet;
    }
}
