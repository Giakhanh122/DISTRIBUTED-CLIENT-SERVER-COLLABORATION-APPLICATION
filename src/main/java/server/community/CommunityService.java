package server.community;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import server.session.UserSessionRegistry;

public class CommunityService {
    private final CommunityRegistry registry;
    private final UserSessionRegistry userSessions;

    public CommunityService(CommunityRegistry registry, UserSessionRegistry userSessions) {
        this.registry = registry;
        this.userSessions = userSessions;
    }

    /** List all communities with membership info for the requesting user. */
    public String listCommunities(String username) {
        List<Community> all = registry.listAll();
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            Community c = all.get(i);
            if (i > 0) sb.append("\n");
            // Format: id\tname\towner\tmemberCount\tisMember
            sb.append(c.communityId()).append("\t")
              .append(c.name()).append("\t")
              .append(c.ownerUsername()).append("\t")
              .append(c.memberCount()).append("\t")
              .append(c.isMember(username));
        }
        return sb.toString();
    }

    /** Create a new community. Returns "OK\n<id>\n<name>" or error string. */
    public String createCommunity(String name, String ownerUsername) {
        if (name == null || name.isBlank()) {
            return "INVALID_NAME";
        }
        if (name.length() > 100) {
            return "NAME_TOO_LONG";
        }
        String trimmed = name.trim();
        for (Community existing : registry.listAll()) {
            if (existing.name().equalsIgnoreCase(trimmed) && existing.ownerUsername().equalsIgnoreCase(ownerUsername)) {
                return "OK\n" + existing.communityId() + "\n" + existing.name();
            }
        }
        Community community = new Community(trimmed, ownerUsername);
        registry.register(community);
        return "OK\n" + community.communityId() + "\n" + community.name();
    }

    /** Join a community. Returns "OK\n<id>\n<name>" or error string. */
    public String joinCommunity(String communityId, String username) {
        Optional<Community> opt = registry.find(communityId);
        if (opt.isEmpty()) {
            return "NOT_FOUND";
        }
        Community c = opt.get();
        if (c.isMember(username)) {
            return "ALREADY_MEMBER";
        }
        c.addMember(username);
        return "OK\n" + c.communityId() + "\n" + c.name();
    }

    /** Leave a community. Returns "OK" or error string. */
    public String leaveCommunity(String communityId, String username) {
        Optional<Community> opt = registry.find(communityId);
        if (opt.isEmpty()) {
            return "NOT_FOUND";
        }
        Community c = opt.get();
        if (!c.isMember(username)) {
            return "NOT_MEMBER";
        }
        c.removeMember(username);
        if (c.memberCount() == 0) {
            registry.remove(communityId);
        } else if (c.isOwner(username)) {
            String nextOwner = c.members().iterator().next();
            c.setOwnerUsername(nextOwner);
        }
        return "OK";
    }

    /** List members of a community. Returns newline-separated "username\tonline" or error. */
    public String listMembers(String communityId, String username) {
        Optional<Community> opt = registry.find(communityId);
        if (opt.isEmpty()) {
            return "NOT_FOUND";
        }
        Community c = opt.get();
        if (!c.isMember(username)) {
            return "NOT_MEMBER";
        }
        Set<String> members = c.members();
        if (members.isEmpty()) return "OK\n";
        StringBuilder sb = new StringBuilder("OK\n");
        boolean first = true;
        for (String member : members) {
            if (!first) sb.append("\n");
            first = false;
            boolean online = userSessions.find(member).isPresent();
            sb.append(member).append("\t").append(online);
        }
        return sb.toString();
    }

    /** Select a community (verify membership). Returns "OK\n<id>\n<name>" or error. */
    public String selectCommunity(String communityId, String username) {
        Optional<Community> opt = registry.find(communityId);
        if (opt.isEmpty()) {
            return "NOT_FOUND";
        }
        Community c = opt.get();
        if (!c.isMember(username)) {
            return "NOT_MEMBER";
        }
        return "OK\n" + c.communityId() + "\n" + c.name();
    }

    /** Get community info. Returns multi-field response or error. */
    public String communityInfo(String communityId, String username) {
        Optional<Community> opt = registry.find(communityId);
        if (opt.isEmpty()) {
            return "NOT_FOUND";
        }
        Community c = opt.get();
        // Format: OK\nid\tname\towner\tmemberCount\tisMember\tcreatedAt
        return "OK\n" + c.communityId() + "\t" + c.name() + "\t" + c.ownerUsername()
                + "\t" + c.memberCount() + "\t" + c.isMember(username) + "\t" + c.createdAt();
    }

    /** Validate membership for broadcast. Returns the community or null. */
    public Community validateMembership(String communityId, String username) {
        Optional<Community> opt = registry.find(communityId);
        if (opt.isEmpty()) return null;
        Community c = opt.get();
        if (!c.isMember(username)) return null;
        return c;
    }
}
