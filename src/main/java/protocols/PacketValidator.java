package protocols;

import java.io.IOException;

public final class PacketValidator {
    private PacketValidator() {}

    static void validatePacket(Packet packet, long sequence) throws IOException {
        if (sequence <= 0) {
            throw new IOException("Sequence must be positive");
        }
        if (packet.op() == OpCode.FILE_CHUNK) {
            throw new IOException("FILE_CHUNK is replaced by raw file bytes");
        }
        validateBody(packet);
    }

    private static void validateBody(Packet packet) throws IOException {
        if (packet.op().requiresStatus() && packet.status().isBlank()) {
            throw new IOException("Empty status for " + packet.op());
        }
        if (!packet.op().requiresReplyTo()) return;
        long replyTo = packet.replyTo();
        boolean earlyFileFailure = packet.op() == OpCode.FILE_RESULT && packet.status().equals("FAIL");
        if (replyTo < 0 || (replyTo == 0 && !earlyFileFailure)) {
            throw new IOException("Invalid replyTo for " + packet.op());
        }
    }

    public static Packet validateResponse(Packet packet, OpCode expectedOp) throws IOException {
        if (packet == null) throw new IOException("Missing " + expectedOp);
        if (packet.op() != expectedOp) {
            throw new IOException("Expected " + expectedOp + ", got " + packet.op());
        }
        return packet;
    }

    public static Packet validateResponse(Packet packet, OpCode expectedOp, long requestSequence) throws IOException {
        validateResponse(packet, expectedOp);
        if (packet.replyTo() != requestSequence) {
            throw new IOException("Expected replyTo " + requestSequence + ", got " + packet.replyTo());
        }
        return packet;
    }

    public static void requireSuccess(Packet response) throws IOException {
        String status = response.status();
        if (!status.equals("OK")) {
            throw new IOException(response.op() + " failed: " + status);
        }
    }

    public static Packet validateRequest(Packet packet, OpCode expectedOp,
                                        int minBodyLength, int maxBodyLength) throws IOException {
        if (packet == null || packet.op() != expectedOp) {
            throw new IOException("Invalid or missing " + expectedOp);
        }
        if (minBodyLength == 0 && maxBodyLength == 0) {
            if (!packet.jsonBody().isEmpty()) throw new IOException("Expected empty body for " + expectedOp);
            return packet;
        }
        int length = packet.bodyLength();
        if (length < minBodyLength || length > maxBodyLength) {
            throw new IOException("Invalid " + expectedOp + " body length: " + length);
        }
        return packet;
    }
}
