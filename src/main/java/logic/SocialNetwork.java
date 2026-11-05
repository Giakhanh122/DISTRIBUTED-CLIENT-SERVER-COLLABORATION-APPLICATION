package logic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * SocialNetwork
 */
public class SocialNetwork {
  private Map<User, Set<User>> adjacencyList = new HashMap<>();

  public void addUser(User user) {
    this.adjacencyList.putIfAbsent(user, new HashSet<>());
  }
}
