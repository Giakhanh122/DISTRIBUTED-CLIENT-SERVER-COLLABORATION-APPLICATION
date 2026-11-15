package protocols;

import java.io.IOException;

/** A typed operation belongs to exactly one opcode group. */
public sealed interface MessageType permits SystemType, ConnectionType, AuthType, UserType,
        ChatType, FileType, CommunityType, DiscoveryType {
    OpCode opcode();
    String name();
    boolean requiresStatus();

    default boolean requiresReplyTo() {
        return requiresStatus() || this == FileType.DECISION;
    }

    static MessageType fromWire(OpCode opcode, String type) throws IOException {
        try {
            return switch (opcode) {
                case SYSTEM -> SystemType.valueOf(type);
                case CONNECTION -> ConnectionType.valueOf(type);
                case AUTH -> AuthType.valueOf(type);
                case USER -> UserType.valueOf(type);
                case CHAT -> ChatType.valueOf(type);
                case FILE -> FileType.valueOf(type);
                case COMMUNITY -> CommunityType.valueOf(type);
                case DISCOVERY -> DiscoveryType.valueOf(type);
            };
        } catch (IllegalArgumentException e) {
            throw new IOException("Unknown type " + type + " for opcode " + opcode, e);
        }
    }
}
