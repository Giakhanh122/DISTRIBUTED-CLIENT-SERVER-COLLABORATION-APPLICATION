package client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import protocols.Json;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketValidator;

/** Community requests and the current selection for one login. */
public final class CommunityClient {
    public record Selection(String id, String name) {}

    private final Supplier<ServerConnection> server;
    private volatile Selection selected;

    CommunityClient(ServerConnection server) {
        this(() -> server);
    }

    CommunityClient(Supplier<ServerConnection> server) {
        this.server = server;
    }

    synchronized void clearSelection() { selected = null; }

    public Selection selected() { return selected; }

    Selection requireSelection() throws IOException {
        Selection selection = selected;
        if (selection == null) throw new IOException("No community selected");
        return selection;
    }

    public List<CommunityInfo> list() throws IOException {
        Packet response = request(Packet.empty(OpCode.COMMUNITY_LIST_REQUEST), OpCode.COMMUNITY_LIST_RESPONSE);
        List<CommunityInfo> communities = new ArrayList<>();
        for (var entry : Json.array(response.jsonBody(), "communities")) {
            communities.add(CommunityInfo.fromJson(Json.asObject(entry)));
        }
        return List.copyOf(communities);
    }

    public Selection create(String name) throws IOException {
        return selectFrom(Packet.json(OpCode.COMMUNITY_CREATE_REQUEST, Json.object("name", name)),
                OpCode.COMMUNITY_CREATE_RESPONSE);
    }

    public Selection join(String id) throws IOException {
        return selectFrom(Packet.json(OpCode.COMMUNITY_JOIN_REQUEST, Json.object("communityId", id)),
                OpCode.COMMUNITY_JOIN_RESPONSE);
    }

    public Selection select(String id) throws IOException {
        return selectFrom(Packet.json(OpCode.COMMUNITY_SELECT_REQUEST, Json.object("communityId", id)),
                OpCode.COMMUNITY_SELECT_RESPONSE);
    }

    public Selection leave() throws IOException {
        return leave(requireSelection().id());
    }

    public Selection leave(String id) throws IOException {
        Selection selection = selected;
        request(Packet.json(OpCode.COMMUNITY_LEAVE_REQUEST, Json.object("communityId", id)),
                OpCode.COMMUNITY_LEAVE_RESPONSE);
        synchronized (this) {
            // Another caller may have selected a different community while leave waited for its reply.
            if (selected != null && selected.id().equals(id)) selected = null;
        }
        return selection;
    }

    JsonArray members() throws IOException {
        Packet response = request(Packet.json(OpCode.COMMUNITY_MEMBERS_REQUEST,
                Json.object("communityId", requireSelection().id())), OpCode.COMMUNITY_MEMBERS_RESPONSE);
        return Json.array(response.jsonBody(), "members");
    }

    JsonObject info() throws IOException {
        Packet response = request(Packet.json(OpCode.COMMUNITY_INFO_REQUEST,
                Json.object("communityId", requireSelection().id())), OpCode.COMMUNITY_INFO_RESPONSE);
        return Json.object(response.jsonBody(), "community");
    }

    private Selection selectFrom(Packet request, OpCode opcode) throws IOException {
        ServerConnection source = server.get();
        Packet response = source.request(request, opcode);
        PacketValidator.requireSuccess(response);
        synchronized (this) {
            if (source != server.get()) throw new IOException("Server connection changed; select the community again");
            selected = new Selection(response.string("communityId"), response.string("name"));
            return selected;
        }
    }

    private Packet request(Packet request, OpCode responseOp) throws IOException {
        ServerConnection connection = server.get();
        Packet response = connection.request(request, responseOp);
        if (connection != server.get()) throw new IOException("Server connection changed; retry the operation");
        PacketValidator.requireSuccess(response);
        return response;
    }
}
