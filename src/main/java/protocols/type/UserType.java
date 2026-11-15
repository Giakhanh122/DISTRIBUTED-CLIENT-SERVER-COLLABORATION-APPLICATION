package protocols.type;

import protocols.OpCode;

/** Operations in the USER opcode group. */
public enum UserType implements MessageType {
    LIST_REQUEST,
    LIST_RESPONSE(true),
    PRESENCE_UPDATE,
    PEER_LOOKUP_REQUEST,
    PEER_LOOKUP_RESPONSE(true),
    PEER_KEY_REQUEST,
    PEER_KEY_RESPONSE(true);

    private final boolean response;

    UserType() { this(false); }
    UserType(boolean response) { this.response = response; }

    @Override public OpCode opcode() { return OpCode.USER; }
    @Override public boolean requiresStatus() { return response; }
}
