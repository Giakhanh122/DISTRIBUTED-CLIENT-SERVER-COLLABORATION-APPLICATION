package protocols.type;

import protocols.OpCode;

/** Operations in the CONNECTION opcode group. */
public enum ConnectionType implements MessageType {
    HELLO,
    HELLO_ACK(true),
    PEER_HELLO,
    PEER_HELLO_ACK(true);

    private final boolean response;

    ConnectionType() { this(false); }
    ConnectionType(boolean response) { this.response = response; }

    @Override public OpCode opcode() { return OpCode.CONNECTION; }
    @Override public boolean requiresStatus() { return response; }
}
