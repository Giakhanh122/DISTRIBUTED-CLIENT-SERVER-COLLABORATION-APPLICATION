package protocols.type;

import protocols.OpCode;

/** Operations in the DISCOVERY opcode group. */
public enum DiscoveryType implements MessageType {
    DISCOVERY_REQUEST,
    DISCOVERY_RESPONSE(true);

    private final boolean response;

    DiscoveryType() { this(false); }
    DiscoveryType(boolean response) { this.response = response; }

    @Override public OpCode opcode() { return OpCode.DISCOVERY; }
    @Override public boolean requiresStatus() { return response; }
}
