package protocols;

import java.io.IOException;

/** Wire opcode identifies a feature group; body.type identifies its operation. */
public enum OpCode {
    SYSTEM(0x00),
    CONNECTION(0x01),
    AUTH(0x02),
    USER(0x03),
    CHAT(0x04),
    FILE(0x05),
    COMMUNITY(0x06),
    DISCOVERY(0x07);

    private final int code;

    OpCode(int code) { this.code = code; }

    public int getCode() { return code; }

    public static OpCode fromCode(int code) throws IOException {
        for (OpCode opcode : values()) {
            if (opcode.code == code) return opcode;
        }
        throw new IOException("Unknown opcode group: " + code);
    }
}
