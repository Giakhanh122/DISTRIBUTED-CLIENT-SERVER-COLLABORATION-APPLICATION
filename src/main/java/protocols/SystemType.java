package protocols;

/** Operations in the SYSTEM opcode group. */
public enum SystemType implements MessageType {
    PING,
    PONG(true),
    KEY_CONFIRM,
    KEY_CONFIRM_ACK(true),
    ERROR;

    private final boolean response;

    SystemType() { this(false); }
    SystemType(boolean response) { this.response = response; }

    @Override public OpCode opcode() { return OpCode.SYSTEM; }
    @Override public boolean requiresStatus() { return response; }
}
