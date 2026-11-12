package server.community;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Community {
    private final String communityId;
    private final String name;
    private volatile String ownerUsername;
    private final Instant createdAt;
    private final Set<String> members = ConcurrentHashMap.newKeySet();

    public Community(String name, String ownerUsername) {
        this.communityId = UUID.randomUUID().toString();
        this.name = name;
        this.ownerUsername = ownerUsername;
        this.createdAt = Instant.now();
        this.members.add(ownerUsername);
    }

    public String communityId() { return communityId; }
    public String name() { return name; }
    public String ownerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }
    public Instant createdAt() { return createdAt; }

    public Set<String> members() {
        return Collections.unmodifiableSet(members);
    }

    public int memberCount() { return members.size(); }

    public boolean isMember(String username) {
        return members.contains(username);
    }

    public boolean addMember(String username) {
        return members.add(username);
    }

    public boolean removeMember(String username) {
        return members.remove(username);
    }

    public boolean isOwner(String username) {
        return ownerUsername.equals(username);
    }
}
