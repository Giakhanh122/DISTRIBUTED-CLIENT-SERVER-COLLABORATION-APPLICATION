package client;

import com.google.gson.JsonObject;
import java.io.IOException;
import protocols.Json;

/** Transport-independent community snapshot consumed by console and GUI. */
public record CommunityInfo(String id, String name, String owner, int memberCount, boolean member) {
    static CommunityInfo fromJson(JsonObject value) throws IOException {
        return new CommunityInfo(Json.string(value, "communityId"), Json.string(value, "name"),
                Json.string(value, "owner"), Json.integer(value, "memberCount"), Json.bool(value, "isMember"));
    }
}
