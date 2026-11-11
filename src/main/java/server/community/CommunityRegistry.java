package server.community;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class CommunityRegistry {
    private final ConcurrentHashMap<String, Community> communities = new ConcurrentHashMap<>();

    public void register(Community community) {
        communities.put(community.communityId(), community);
    }

    public Optional<Community> find(String communityId) {
        return Optional.ofNullable(communities.get(communityId));
    }

    public boolean remove(String communityId) {
        return communities.remove(communityId) != null;
    }

    public List<Community> listAll() {
        return List.copyOf(communities.values());
    }
}
