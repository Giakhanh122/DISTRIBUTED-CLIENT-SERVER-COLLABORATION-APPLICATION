package server.community;

import com.google.gson.JsonObject;
import java.io.IOException;
import network.PacketChannel;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketValidator;
import server.session.UserSession;

/** Maps community wire requests to the community service and correlated responses. */
public final class CommunityRequestHandler {
    private static final int MAX_COMMUNITY_BYTES = 4096;
    private final CommunityService communityService;

    public CommunityRequestHandler(CommunityService communityService) { this.communityService = communityService; }

    public void handle(Packet packet, UserSession session) throws IOException {
        switch (packet.op()) {
            case COMMUNITY_LIST_REQUEST -> handleCommunityList(packet, session);
            case COMMUNITY_CREATE_REQUEST -> handleCommunityCreate(packet, session);
            case COMMUNITY_JOIN_REQUEST -> handleCommunityJoin(packet, session);
            case COMMUNITY_LEAVE_REQUEST -> handleCommunityLeave(packet, session);
            case COMMUNITY_MEMBERS_REQUEST -> handleCommunityMembers(packet, session);
            case COMMUNITY_SELECT_REQUEST -> handleCommunitySelect(packet, session);
            case COMMUNITY_INFO_REQUEST -> handleCommunityInfo(packet, session);
            default -> throw new IOException("Unexpected community request: " + packet.op());
        }
    }

    private void handleCommunityList(Packet request, UserSession session) throws IOException {
        PacketChannel channel = session.channel();
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_LIST_REQUEST, 0, 0);
        JsonObject result = communityService.listCommunities(session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_LIST_RESPONSE, request.sequence(), result));
    }

    private void handleCommunityCreate(Packet request, UserSession session) throws IOException {
        PacketChannel channel = session.channel();
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_CREATE_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String name = request.string("name");
        JsonObject result = communityService.createCommunity(name, session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_CREATE_RESPONSE, request.sequence(), result));
    }

    private void handleCommunityJoin(Packet request, UserSession session) throws IOException {
        PacketChannel channel = session.channel();
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_JOIN_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.string("communityId");
        JsonObject result = communityService.joinCommunity(communityId, session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_JOIN_RESPONSE, request.sequence(), result));
    }

    private void handleCommunityLeave(Packet request, UserSession session) throws IOException {
        PacketChannel channel = session.channel();
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_LEAVE_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.string("communityId");
        JsonObject result = communityService.leaveCommunity(communityId, session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_LEAVE_RESPONSE, request.sequence(), result));
    }

    private void handleCommunityMembers(Packet request, UserSession session) throws IOException {
        PacketChannel channel = session.channel();
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_MEMBERS_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.string("communityId");
        JsonObject result = communityService.listMembers(communityId, session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_MEMBERS_RESPONSE, request.sequence(), result));
    }

    private void handleCommunitySelect(Packet request, UserSession session) throws IOException {
        PacketChannel channel = session.channel();
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_SELECT_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.string("communityId");
        JsonObject result = communityService.selectCommunity(communityId, session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_SELECT_RESPONSE, request.sequence(), result));
    }

    private void handleCommunityInfo(Packet request, UserSession session) throws IOException {
        PacketChannel channel = session.channel();
        PacketValidator.validateRequest(request, OpCode.COMMUNITY_INFO_REQUEST, 1, MAX_COMMUNITY_BYTES);
        String communityId = request.string("communityId");
        JsonObject result = communityService.communityInfo(communityId, session.username());
        channel.send(Packet.reply(OpCode.COMMUNITY_INFO_RESPONSE, request.sequence(), result));
    }
}
