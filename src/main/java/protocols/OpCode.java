package protocols;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public enum OpCode {
    //Cilent-Server connection protocol
    HELLO(0x01),
    HELLO_ACK(0x02),
    //Cilent-Server management protocol
    AUTH_REQUEST(0x10),
    AUTH_RESPONSE(0x11),
    USER_LIST_REQUEST(0x12),
    USER_LIST_RESPONSE(0x13),
    PEER_LOOKUP_REQUEST(0x14),
    PEER_LOOKUP_RESPONSE(0x15),
    LOGOUT_REQUEST(0x16),
    LOGOUT_RESPONSE(0x17),
    PRESENCE_UPDATE(0x18),
    //Peer connection protocol
    PEER_HELLO(0x20),
    PEER_HELLO_ACK(0x21),    
    //Messaging protocol
    CHAT_MESSAGE(0x30),
    CHAT_RECEIPT(0x31),
    TYPING(0x32), //optional
    //File transfer protocol
    FILE_OFFER(0x40),
    FILE_DECISION(0x41),
    FILE_CHUNK(0x42),
    FILE_END(0x43),
    FILE_RESULT(0x44),
    FILE_CANCEL(0x45),
    //UDP control protocol
    DISCOVER_REQUEST(0x50),
    DISCOVER_RESPONSE(0x51),
    HEARTBEAT(0x52),
    HEARTBEAT_ACK(0x53),
    //Error protocol
    ERROR(0x7F); //maybe

    private final int code;
    private static final Map<Integer, OpCode> BY_CODE = new HashMap<> ();

    static {
        for (OpCode op : values()) {
            BY_CODE.put(op.code, op);
        }
    }

    OpCode(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static OpCode fromCode(int code) throws IOException {
        OpCode op = BY_CODE.get(code);
        if (op != null) return op;
        throw new IOException("Unknown OP: " + code);
    }
}
