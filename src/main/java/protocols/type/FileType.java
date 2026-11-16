package protocols.type;

import protocols.OpCode;

/** Operations in the FILE opcode group. */
public enum FileType implements MessageType {
    OFFER,
    DECISION,
    CANCEL,
    TRANSFER_START,
    READY(true),
    END,
    RESULT(true),
    BROADCAST_UPLOAD_REQUEST,
    BROADCAST_UPLOAD_RESPONSE(true),
    BROADCAST_OFFER,
    BROADCAST_DECISION,
    BROADCAST_DOWNLOAD_RESPONSE(true),
    BROADCAST_UPLOAD_START,
    BROADCAST_DOWNLOAD_START;

    private final boolean response;

    FileType() { this(false); }
    FileType(boolean response) { this.response = response; }

    @Override public OpCode opcode() { return OpCode.FILE; }
    @Override public boolean requiresStatus() { return response; }
}
