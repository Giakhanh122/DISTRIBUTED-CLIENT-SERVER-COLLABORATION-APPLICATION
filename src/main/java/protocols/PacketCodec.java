package protocols;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Wire format: OP:u8 | RequestID:i32 | PayloadLength:i32 | Data:N bytes.
 * All multibyte numbers use big-endian byte order.
 */
public final class PacketCodec {
    public static final int HEADER_LENGTH = 9;
    public static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;

    private PacketCodec() {}

    public static Packet read(InputStream input) throws IOException {
        DataInputStream reader = new DataInputStream(input);
        int code = reader.read();
        if (code == -1) {
            return null;
        }

        OpCode op = OpCode.fromCode(code);
        int requestId = reader.readInt();
        int payloadLength = reader.readInt();
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new IOException("Invalid payload length: " + payloadLength);
        }

        byte[] data = new byte[payloadLength];
        reader.readFully(data);
        return new Packet(op, requestId, data);
    }

    public static void write(OutputStream output, Packet packet) throws IOException {
        if (packet.payloadLength() > MAX_PAYLOAD_LENGTH) {
            throw new IOException("Invalid payload length: " + packet.payloadLength());
        }
        DataOutputStream writer = new DataOutputStream(output);
        writer.writeByte(packet.op().getCode());
        writer.writeInt(packet.requestId());
        writer.writeInt(packet.payloadLength());
        writer.write(packet.data());
        writer.flush();
    }
}
