package protocols;

import com.google.gson.JsonObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/** Stateless codec for [UTF-8 byte length:i32 big-endian][JSON envelope]. */
public final class PacketCodec {
    public static final int LENGTH_PREFIX_BYTES = Integer.BYTES;
    public static final int MAX_JSON_LENGTH = 1024 * 1024;

    private PacketCodec() {}

    public static Packet read(InputStream input) throws IOException {
        byte[] json = readFrame(input);
        return json == null ? null : decode(json);
    }

    public static void write(OutputStream output, Packet packet, long sequence) throws IOException {
        byte[] json = encode(packet, sequence);
        validateLength(json.length);

        DataOutputStream writer = new DataOutputStream(output);
        writer.writeInt(json.length);
        writer.write(json);
        writer.flush();
    }

    private static byte[] readFrame(InputStream input) throws IOException {
        DataInputStream reader = new DataInputStream(input);
        byte[] prefix = reader.readNBytes(LENGTH_PREFIX_BYTES);
        if (prefix.length == 0) {
            return null;
        }
        if (prefix.length != LENGTH_PREFIX_BYTES) {
            throw new EOFException("Incomplete JSON length");
        }

        int length = ByteBuffer.wrap(prefix).getInt();
        validateLength(length);
        byte[] json = new byte[length];
        reader.readFully(json);
        return json;
    }

    private static Packet decode(byte[] json) throws IOException {
        JsonObject envelope = Json.parse(json);
        if (envelope.size() != 3) {
            throw new IOException("Expected opcode, sequence, body");
        }

        OpCode opcode = OpCode.fromCode(Json.integer(envelope, "opcode"));
        long sequence = Json.longValue(envelope, "sequence");
        JsonObject body = Json.object(envelope, "body");
        Packet packet = new Packet(opcode, sequence, body);
        PacketValidator.validatePacket(packet, sequence);
        return packet;
    }

    private static byte[] encode(Packet packet, long sequence) throws IOException {
        PacketValidator.validatePacket(packet, sequence);
        return Json.bytes(Json.object(
                "opcode", packet.op().getCode(),
                "sequence", sequence,
                "body", packet.jsonBody()));
    }

    private static void validateLength(int length) throws IOException {
        if (length <= 0 || length > MAX_JSON_LENGTH) {
            throw new IOException("Invalid JSON length: " + length);
        }
    }
}
