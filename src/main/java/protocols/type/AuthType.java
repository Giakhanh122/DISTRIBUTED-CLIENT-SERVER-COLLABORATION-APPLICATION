package protocols.type;

import protocols.OpCode;

/** Operations in the AUTH opcode group. */
public enum AuthType implements MessageType {
    LOGIN_REQUEST,
    LOGIN_RESPONSE(true),
    LOGOUT_REQUEST,
    LOGOUT_RESPONSE(true);

    private final boolean response;

    AuthType() { this(false); }
    AuthType(boolean response) { this.response = response; }

    @Override public OpCode opcode() { return OpCode.AUTH; }
    @Override public boolean requiresStatus() { return response; }
}
