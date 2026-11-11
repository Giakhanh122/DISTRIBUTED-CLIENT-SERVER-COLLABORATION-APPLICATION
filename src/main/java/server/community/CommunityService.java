package server.community;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Optional;
import protocols.Json;
import server.session.UserSessionRegistry;

public class CommunityService {
    private final CommunityRegistry registry;
    private final UserSessionRegistry userSessions;

    public CommunityService(CommunityRegistry registry, UserSessionRegistry userSessions) {
        this.registry = registry;
        this.userSessions = userSessions;
    }

    public JsonObject listCommunities(String username) {
        JsonArray communities = new JsonArray();
        for (Community community : registry.listAll()) communities.add(describe(community, username));
        return Json.object("status", "OK", "communities", communities);
    }

    public JsonObject createCommunity(String name, String ownerUsername) {
        if (name == null || name.isBlank()) return status("INVALID_NAME");
        if (name.length() > 100) return status("NAME_TOO_LONG");
        Community community = new Community(name.trim(), ownerUsername);
        registry.register(community);
        return selected(community);
    }

    public JsonObject joinCommunity(String communityId, String username) {
        Optional<Community> found = registry.find(communityId);
        if (found.isEmpty()) return status("NOT_FOUND");
        Community community = found.get();
        if (community.isMember(username)) return status("ALREADY_MEMBER");
        community.addMember(username);
        return selected(community);
    }

    public JsonObject leaveCommunity(String communityId, String username) {
        Optional<Community> found = registry.find(communityId);
        if (found.isEmpty()) return status("NOT_FOUND");
        Community community = found.get();
        if (!community.isMember(username)) return status("NOT_MEMBER");
        if (community.isOwner(username)) return status("OWNER_CANNOT_LEAVE");
        community.removeMember(username);
        return status("OK");
    }

    public JsonObject listMembers(String communityId, String username) {
        Optional<Community> found = registry.find(communityId);
        if (found.isEmpty()) return status("NOT_FOUND");
        Community community = found.get();
        if (!community.isMember(username)) return status("NOT_MEMBER");
        JsonArray members = new JsonArray();
        community.members().stream().sorted().forEach(member -> members.add(
                Json.object("username", member, "online", userSessions.find(member).isPresent())));
        return Json.object("status", "OK", "members", members);
    }

    public JsonObject selectCommunity(String communityId, String username) {
        Optional<Community> found = registry.find(communityId);
        if (found.isEmpty()) return status("NOT_FOUND");
        if (!found.get().isMember(username)) return status("NOT_MEMBER");
        return selected(found.get());
    }

    public JsonObject communityInfo(String communityId, String username) {
        Optional<Community> found = registry.find(communityId);
        if (found.isEmpty()) return status("NOT_FOUND");
        return Json.object("status", "OK", "community", describe(found.get(), username));
    }

    public Community validateMembership(String communityId, String username) {
        return registry.find(communityId).filter(c -> c.isMember(username)).orElse(null);
    }

    private static JsonObject status(String status) {
        return Json.object("status", status);
    }

    private static JsonObject selected(Community community) {
        return Json.object("status", "OK", "communityId", community.communityId(), "name", community.name());
    }

    private static JsonObject describe(Community community, String username) {
        return Json.object("communityId", community.communityId(), "name", community.name(),
                "owner", community.ownerUsername(), "memberCount", community.memberCount(),
                "isMember", community.isMember(username), "createdAt", community.createdAt().toString());
    }
}
