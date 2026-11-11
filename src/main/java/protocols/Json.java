package protocols;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class Json {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private Json() {}

    public static JsonObject object(Object... fields) {
        if (fields.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs");
        JsonObject result = new JsonObject();
        for (int i = 0; i < fields.length; i += 2) {
            result.add((String) fields[i], GSON.toJsonTree(fields[i + 1]));
        }
        return result;
    }

    public static byte[] bytes(JsonElement value) {
        return GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    public static JsonObject parse(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = readValue(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT || !value.isJsonObject()) {
                throw new IOException("Expected exactly one JSON object");
            }
            return value.getAsJsonObject();
        } catch (IllegalStateException | NumberFormatException e) {
            throw new IOException("Invalid JSON", e);
        }
    }

    private static JsonElement readValue(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw new IOException("JSON nesting exceeds limit");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject result = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (result.has(name)) throw new IOException("Duplicate JSON field: " + name);
                    result.add(name, readValue(reader, depth + 1));
                }
                reader.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                JsonArray result = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) result.add(readValue(reader, depth + 1));
                reader.endArray();
                yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> JsonParser.parseString(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Unexpected JSON token");
        };
    }

    private static JsonElement field(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) throw new IOException("Missing JSON field: " + name);
        return value;
    }

    public static String string(JsonObject object, String name) throws IOException {
        JsonElement value = field(object, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Expected string: " + name);
        }
        return value.getAsString();
    }

    public static long longValue(JsonObject object, String name) throws IOException {
        JsonElement value = field(object, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("-?(0|[1-9][0-9]*)")) {
            throw new IOException("Expected integer: " + name);
        }
        try {
            return Long.parseLong(value.getAsString());
        } catch (NumberFormatException e) {
            throw new IOException("Integer out of range: " + name, e);
        }
    }

    public static int integer(JsonObject object, String name) throws IOException {
        long value = longValue(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("Integer out of range: " + name);
        }
        return (int) value;
    }

    public static boolean bool(JsonObject object, String name) throws IOException {
        JsonElement value = field(object, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("Expected boolean: " + name);
        }
        return value.getAsBoolean();
    }

    public static JsonObject object(JsonObject object, String name) throws IOException {
        JsonElement value = field(object, name);
        if (!value.isJsonObject()) throw new IOException("Expected object: " + name);
        return value.getAsJsonObject();
    }

    public static JsonArray array(JsonObject object, String name) throws IOException {
        JsonElement value = field(object, name);
        if (!value.isJsonArray()) throw new IOException("Expected array: " + name);
        return value.getAsJsonArray();
    }

    public static JsonObject asObject(JsonElement value) throws IOException {
        if (!value.isJsonObject()) throw new IOException("Expected object in array");
        return value.getAsJsonObject();
    }
}
