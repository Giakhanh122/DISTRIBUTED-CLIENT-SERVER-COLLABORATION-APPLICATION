package client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import protocols.Json;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketValidator;

/** Community requests and the current selection for one login. */
public final class CommunityClient {
    public record Selection(String id, String name) {}

    private final ServerConnection server;
    private volatile Selection selected;

    CommunityClient(ServerConnection server) {
        this.server = server;
    }

    public Selection selected() { return selected; }

    Selection requireSelection() throws IOException {
        Selection selection = selected;
        if (selection == null) throw new IOException("No active community. Use /community select <id> first.");
        return selection;
    }

    public JsonArray list() throws IOException {
        Packet response = request(Packet.empty(OpCode.COMMUNITY_LIST_REQUEST), OpCode.COMMUNITY_LIST_RESPONSE);
        return Json.array(response.jsonBody(), "communities");
    }

    public Selection create(String name) throws IOException {
        return selectFrom(request(Packet.json(OpCode.COMMUNITY_CREATE_REQUEST, Json.object("name", name)),
                OpCode.COMMUNITY_CREATE_RESPONSE));
    }

    public Selection join(String id) throws IOException {
        return selectFrom(request(Packet.json(OpCode.COMMUNITY_JOIN_REQUEST, Json.object("communityId", id)),
                OpCode.COMMUNITY_JOIN_RESPONSE));
    }

    public Selection select(String id) throws IOException {
        return selectFrom(request(Packet.json(OpCode.COMMUNITY_SELECT_REQUEST, Json.object("communityId", id)),
                OpCode.COMMUNITY_SELECT_RESPONSE));
    }

    public Selection leave() throws IOException {
        return leave(requireSelection().id());
    }

    public Selection leave(String id) throws IOException {
        Selection selection = selected;
        request(Packet.json(OpCode.COMMUNITY_LEAVE_REQUEST, Json.object("communityId", id)),
                OpCode.COMMUNITY_LEAVE_RESPONSE);
        if (selection != null && selection.id().equals(id)) selected = null;
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

    private Selection selectFrom(Packet response) throws IOException {
        Selection selection = new Selection(response.string("communityId"), response.string("name"));
        selected = selection;
        return selection;
    }

    private Packet request(Packet request, OpCode responseOp) throws IOException {
        Packet response = server.request(request, responseOp);
        PacketValidator.requireSuccess(response);
        return response;
    }
}
