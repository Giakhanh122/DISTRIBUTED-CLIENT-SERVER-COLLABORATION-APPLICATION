package protocols.type;

import protocols.OpCode;

/** Operations in the FILE opcode group. */
public enum FileType implements MessageType {
    FILE_OFFER,
    FILE_DECISION,
    FILE_CANCEL,
    FILE_CANCEL_ACK(true),
    FILE_TRANSFER_START,
    FILE_READY(true),
    FILE_END,
    FILE_RESULT(true),
    FILE_BROADCAST_UPLOAD_REQUEST,
    FILE_BROADCAST_UPLOAD_RESPONSE(true),
    FILE_BROADCAST_OFFER,
    FILE_BROADCAST_DECISION,
    FILE_BROADCAST_DOWNLOAD_RESPONSE(true),
    FILE_BROADCAST_UPLOAD_START,
    FILE_BROADCAST_DOWNLOAD_START;

    private final boolean response;

    FileType() { this(false); }
    FileType(boolean response) { this.response = response; }

    @Override public OpCode opcode() { return OpCode.FILE; }
    @Override public boolean requiresStatus() { return response; }
}
