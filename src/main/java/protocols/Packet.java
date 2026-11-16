package protocols;

import java.io.IOException;
import java.util.Objects;

import com.google.gson.JsonObject;

import protocols.type.FileType;
import protocols.type.MessageType;

/** One JSON envelope: opcode, sequence, body. */
public final class Packet {
    public static final int PROTOCOL_VERSION = 5;
    private final MessageType type;
    private final long sequence;
    private final JsonObject body;

    Packet(MessageType type, long sequence, JsonObject body) {
        this.type = Objects.requireNonNull(type);
        this.sequence = sequence;
        this.body = Objects.requireNonNull(body).deepCopy();
    }

    public static Packet json(MessageType type, JsonObject body) {
        JsonObject result = body.deepCopy();
        if (result.has("type")) throw new IllegalArgumentException("type is set by the packet factory");
        result.addProperty("type", type.name());
        return new Packet(type, 0, result);
    }
    public static Packet empty(MessageType type) { return json(type, new JsonObject()); }

    public static Packet reply(MessageType type, long replyTo, JsonObject body) {
        JsonObject result = new JsonObject();
        result.addProperty("replyTo", replyTo);
        body.entrySet().forEach(entry ->
            result.add(entry.getKey(), entry.getValue().deepCopy())
        );
        return json(type, result);
    }

    public static Packet status(MessageType type, long replyTo, String status) {
        return reply(type, replyTo, Json.object("status", status));
    }

    public static Packet failure(long replyTo, String reason) {
        return reply(FileType.RESULT, replyTo, Json.object("status", "FAIL",
                "reason", Objects.requireNonNullElse(reason, "File transfer failed")));
    }

    public String string(String name) throws IOException { return Json.string(body, name); }
    public int integer(String name) throws IOException { return Json.integer(body, name); }
    public long longValue(String name) throws IOException { return Json.longValue(body, name); }
    public boolean bool(String name) throws IOException { return Json.bool(body, name); }
    public String status() throws IOException { return string("status"); }
    public long replyTo() throws IOException { return longValue("replyTo"); }
    public OpCode op() { return type.opcode(); }
    public MessageType type() { return type; }
    public long sequence() { return sequence; }
    public JsonObject jsonBody() { return body.deepCopy(); }
    public int bodyLength() { return Json.bytes(body).length; }
}
