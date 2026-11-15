package server.auth;

import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

public class AuthService {
    // Tài khoản mẫu để thử luồng đăng nhập.
    private final Map<String, String> accounts = demoAccounts();

    private static Map<String, String> demoAccounts() {
        Map<String, String> accounts = new HashMap<>();
        accounts.put("alice", "alice123");
        accounts.put("bob", "bob123");
        accounts.put("admin1", "password1");
        for (int i = 1; i <= 17; i++) {
            accounts.put(String.format(Locale.ROOT, "user%02d", i), "demo123");
        }
        return Map.copyOf(accounts);
    }

    public boolean authenticate(String username, String password) {
        if (username == null || password == null) {
            return false;
        }

        String expectedPassword = accounts.get(username);
        if (expectedPassword == null) return false;

        return expectedPassword.equals(password);
    }
}
