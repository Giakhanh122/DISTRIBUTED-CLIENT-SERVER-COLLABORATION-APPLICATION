package server.auth;

import java.util.Map;

public class AuthService {
    // Tài khoản mẫu để thử luồng đăng nhập.
    private final Map<String, String> accounts = Map.of(
        "alice", "alice123",
        "bob", "bob123"
    );

    public boolean authenticate(String username, String password) {
        if (username == null || password == null) {
            return false;
        }

        String expectedPassword = accounts.get(username);
        if (expectedPassword == null) return false;

        return expectedPassword.equals(password);
    }
}
