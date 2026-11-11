package protocols;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Objects;

/** One JSON envelope: opcode, sequence, body. */
public final class Packet {
    public static final int PROTOCOL_VERSION = 1;
    private final OpCode op;
    private final long sequence;
    private final JsonObject body;

    Packet(OpCode op, long sequence, JsonObject body) {
        this.op = Objects.requireNonNull(op);
        this.sequence = sequence;
        this.body = Objects.requireNonNull(body).deepCopy();
    }

    public static Packet json(OpCode op, JsonObject body) { return new Packet(op, 0, body); }
    public static Packet empty(OpCode op) { return json(op, new JsonObject()); }

    public static Packet reply(OpCode op, long replyTo, JsonObject body) {
        JsonObject result = new JsonObject();
        result.addProperty("replyTo", replyTo);
        body.entrySet().forEach(entry ->
            result.add(entry.getKey(), entry.getValue().deepCopy())
        );
        return json(op, result);
    }

    public static Packet status(OpCode op, long replyTo, String status) {
        return reply(op, replyTo, Json.object("status", status));
    }

    public static Packet failure(long replyTo, String reason) {
        return reply(OpCode.FILE_RESULT, replyTo, Json.object("status", "FAIL",
                "reason", Objects.requireNonNullElse(reason, "File transfer failed")));
    }

    public String string(String name) throws IOException { return Json.string(body, name); }
    public int integer(String name) throws IOException { return Json.integer(body, name); }
    public long longValue(String name) throws IOException { return Json.longValue(body, name); }
    public String status() throws IOException { return string("status"); }
    public long replyTo() throws IOException { return longValue("replyTo"); }
    public OpCode op() { return op; }
    public long sequence() { return sequence; }
    public JsonObject jsonBody() { return body.deepCopy(); }
    public int bodyLength() { return Json.bytes(body).length; }
}
