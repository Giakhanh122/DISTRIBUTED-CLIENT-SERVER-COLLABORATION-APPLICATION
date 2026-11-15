package protocols.type;

import protocols.OpCode;

/** Operations in the CHAT opcode group. */
public enum ChatType implements MessageType {
    MESSAGE,
    BROADCAST,
    RECEIPT,
    TYPING,
    SESSION_REPORT,
    SESSION_REPORT_RESPONSE(true),
    SESSION_LIST_REQUEST,
    SESSION_LIST_RESPONSE(true);

    private final boolean response;

    ChatType() { this(false); }
    ChatType(boolean response) { this.response = response; }

    @Override public OpCode opcode() { return OpCode.CHAT; }
    @Override public boolean requiresStatus() { return response; }
}
