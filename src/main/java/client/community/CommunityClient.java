package client.community;

import protocols.type.CommunityType;
import protocols.type.MessageType;

import client.connection.ServerConnection;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import protocols.Json;
import protocols.Packet;
import protocols.PacketValidator;

/** Community requests and the current selection for one login. */
public final class CommunityClient {
    public record Selection(String id, String name) {}

    private final Supplier<ServerConnection> server;
    private volatile Selection selected;

    public CommunityClient(ServerConnection server) {
        this(() -> server);
    }

    public CommunityClient(Supplier<ServerConnection> server) {
        this.server = server;
    }

    public synchronized void clearSelection() { selected = null; }

    public Selection selected() { return selected; }

    public Selection requireSelection() throws IOException {
        Selection selection = selected;
        if (selection == null) throw new IOException("No community selected");
        return selection;
    }

    public List<CommunityInfo> list() throws IOException {
        Packet response = request(Packet.empty(CommunityType.LIST_REQUEST), CommunityType.LIST_RESPONSE);
        List<CommunityInfo> communities = new ArrayList<>();
        for (var entry : Json.array(response.jsonBody(), "communities")) {
            communities.add(CommunityInfo.fromJson(Json.asObject(entry)));
        }
        return List.copyOf(communities);
    }

    public Selection create(String name) throws IOException {
        return selectFrom(Packet.json(CommunityType.CREATE_REQUEST, Json.object("name", name)),
                CommunityType.CREATE_RESPONSE);
    }

    public Selection join(String id) throws IOException {
        return selectFrom(Packet.json(CommunityType.JOIN_REQUEST, Json.object("communityId", id)),
                CommunityType.JOIN_RESPONSE);
    }

    public Selection select(String id) throws IOException {
        return selectFrom(Packet.json(CommunityType.SELECT_REQUEST, Json.object("communityId", id)),
                CommunityType.SELECT_RESPONSE);
    }

    public Selection leave() throws IOException {
        return leave(requireSelection().id());
    }

    public Selection leave(String id) throws IOException {
        Selection selection = selected;
        request(Packet.json(CommunityType.LEAVE_REQUEST, Json.object("communityId", id)),
                CommunityType.LEAVE_RESPONSE);
        synchronized (this) {
            // Another caller may have selected a different community while leave waited for its reply.
            if (selected != null && selected.id().equals(id)) selected = null;
        }
        return selection;
    }

    public JsonArray members() throws IOException {
        return members(requireSelection().id());
    }

    public JsonArray members(String communityId) throws IOException {
        if (communityId == null || communityId.isBlank() || communityId.length() > 256) {
            throw new IOException("Invalid community ID");
        }
        Packet response = request(Packet.json(CommunityType.MEMBERS_REQUEST,
                Json.object("communityId", communityId)), CommunityType.MEMBERS_RESPONSE);
        return Json.array(response.jsonBody(), "members");
    }

    public JsonObject info() throws IOException {
        Packet response = request(Packet.json(CommunityType.INFO_REQUEST,
                Json.object("communityId", requireSelection().id())), CommunityType.INFO_RESPONSE);
        return Json.object(response.jsonBody(), "community");
    }

    private Selection selectFrom(Packet request, MessageType responseType) throws IOException {
        ServerConnection source = server.get();
        Packet response = source.request(request, responseType);
        if (responseType == CommunityType.CREATE_RESPONSE) {
            switch (response.status()) {
                case "NAME_TAKEN" -> throw new IOException("This community name is already taken. Please enter a different name.");
                case "INVALID_NAME" -> throw new IOException("Please enter a valid community name.");
                case "NAME_TOO_LONG" -> throw new IOException("Community names can contain up to 100 characters. Please enter a different name.");
                default -> { }
            }
        }
        PacketValidator.requireSuccess(response);
        synchronized (this) {
            if (source != server.get()) throw new IOException("Server connection changed; select the community again");
            selected = new Selection(response.string("communityId"), response.string("name"));
            return selected;
        }
    }

    private Packet request(Packet request, MessageType responseOp) throws IOException {
        ServerConnection connection = server.get();
        Packet response = connection.request(request, responseOp);
        if (connection != server.get()) throw new IOException("Server connection changed; retry the operation");
        PacketValidator.requireSuccess(response);
        return response;
    }
}
