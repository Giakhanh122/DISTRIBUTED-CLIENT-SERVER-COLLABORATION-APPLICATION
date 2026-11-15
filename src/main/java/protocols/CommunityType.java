package protocols;

/** Operations in the COMMUNITY opcode group. */
public enum CommunityType implements MessageType {
    LIST_REQUEST,
    LIST_RESPONSE(true),
    CREATE_REQUEST,
    CREATE_RESPONSE(true),
    JOIN_REQUEST,
    JOIN_RESPONSE(true),
    LEAVE_REQUEST,
    LEAVE_RESPONSE(true),
    MEMBERS_REQUEST,
    MEMBERS_RESPONSE(true),
    SELECT_REQUEST,
    SELECT_RESPONSE(true),
    INFO_REQUEST,
    INFO_RESPONSE(true);

    private final boolean response;

    CommunityType() { this(false); }
    CommunityType(boolean response) { this.response = response; }

    @Override public OpCode opcode() { return OpCode.COMMUNITY; }
    @Override public boolean requiresStatus() { return response; }
}
