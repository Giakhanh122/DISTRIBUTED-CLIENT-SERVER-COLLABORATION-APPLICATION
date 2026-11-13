package protocols;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public enum OpCode {
    // Cilent-Server connection protocol
    HELLO(0x01),
    HELLO_ACK(0x02),
    // Cilent-Server management protocol
    AUTH_REQUEST(0x10),
    AUTH_RESPONSE(0x11),
    USER_LIST_REQUEST(0x12),
    USER_LIST_RESPONSE(0x13),
    PEER_LOOKUP_REQUEST(0x14),
    PEER_LOOKUP_RESPONSE(0x15),
    LOGOUT_REQUEST(0x16),
    LOGOUT_RESPONSE(0x17),
    PRESENCE_UPDATE(0x18),
    // Peer connection protocol
    PEER_HELLO(0x20),
    PEER_HELLO_ACK(0x21),
    // Messaging protocol
    CHAT_MESSAGE(0x30),
    CHAT_MESSAGE_BROADCAST(0x31),
    CHAT_RECEIPT(0x32),
    TYPING(0x33), // optional
    // File transfer protocol
    FILE_OFFER(0x40),
    FILE_DECISION(0x41),
    FILE_CHUNK(0x42), // reserved: file data now streams as raw bytes
    FILE_END(0x43),
    FILE_RESULT(0x44),
    // Broadcast file transfer protocol
    FILE_BROADCAST_UPLOAD_REQUEST(0x45),
    FILE_BROADCAST_UPLOAD_RESPONSE(0x46),
    FILE_BROADCAST_OFFER(0x47),
    FILE_BROADCAST_DECISION(0x48),
    FILE_BROADCAST_DOWNLOAD_REQUEST(0x49),
    FILE_BROADCAST_DOWNLOAD_RESPONSE(0x4A),
    FILE_BROADCAST_UPLOAD_START(0x4B),
    FILE_BROADCAST_DOWNLOAD_START(0x4C),
    // UDP discovery and TCP control-connection liveness
    DISCOVER_REQUEST(0x50),
    DISCOVER_RESPONSE(0x51),
    HEARTBEAT(0x52),
    HEARTBEAT_ACK(0x53),
    // P2P session monitoring (client-server metadata only)
    CHAT_SESSION_REPORT(0x60),
    CHAT_SESSION_REPORT_RESPONSE(0x61),
    CHAT_SESSION_LIST_REQUEST(0x62),
    CHAT_SESSION_LIST_RESPONSE(0x63),
    // Community management protocol
    COMMUNITY_LIST_REQUEST(0x70),
    COMMUNITY_LIST_RESPONSE(0x71),
    COMMUNITY_CREATE_REQUEST(0x72),
    COMMUNITY_CREATE_RESPONSE(0x73),
    COMMUNITY_JOIN_REQUEST(0x74),
    COMMUNITY_JOIN_RESPONSE(0x75),
    COMMUNITY_LEAVE_REQUEST(0x76),
    COMMUNITY_LEAVE_RESPONSE(0x77),
    COMMUNITY_MEMBERS_REQUEST(0x78),
    COMMUNITY_MEMBERS_RESPONSE(0x79),
    COMMUNITY_SELECT_REQUEST(0x7A),
    COMMUNITY_SELECT_RESPONSE(0x7B),
    COMMUNITY_INFO_REQUEST(0x7C),
    COMMUNITY_INFO_RESPONSE(0x7D),
    // Error protocol
    ERROR(0x7F); // maybe

    private final int code;
    private static final Map<Integer, OpCode> BY_CODE = new HashMap<>();

    static {
        for (OpCode op : values()) {
            BY_CODE.put(op.code, op);
        }
    }

    OpCode(int code) {
        this.code = code;
    }

    public boolean requiresStatus() {
        return switch (this) {
            case HELLO_ACK, AUTH_RESPONSE, USER_LIST_RESPONSE, PEER_LOOKUP_RESPONSE,
                    LOGOUT_RESPONSE, PEER_HELLO_ACK, FILE_RESULT,
                    FILE_BROADCAST_UPLOAD_RESPONSE, FILE_BROADCAST_DOWNLOAD_RESPONSE,
                    DISCOVER_RESPONSE, HEARTBEAT_ACK, CHAT_SESSION_REPORT_RESPONSE, CHAT_SESSION_LIST_RESPONSE,
                    COMMUNITY_LIST_RESPONSE, COMMUNITY_CREATE_RESPONSE, COMMUNITY_JOIN_RESPONSE,
                    COMMUNITY_LEAVE_RESPONSE, COMMUNITY_MEMBERS_RESPONSE,
                    COMMUNITY_SELECT_RESPONSE, COMMUNITY_INFO_RESPONSE -> true;
            default -> false;
        };
    }

    public boolean requiresReplyTo() {
        return requiresStatus() || this == FILE_DECISION;
    }

    public int getCode() {
        return code;
    }

    public static OpCode fromCode(int code) throws IOException {
        OpCode op = BY_CODE.get(code);
        if (op != null)
            return op;
        throw new IOException("Unknown OP: " + code);
    }
}
