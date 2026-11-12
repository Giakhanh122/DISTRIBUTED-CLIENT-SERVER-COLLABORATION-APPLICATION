package server.session;

import java.net.Socket;

public record UserSession(
        String username,
        String ip,
        int port,
        Socket clientSocket) {

    public UserSession(String username, String ip, int port) {
        this(username, ip, port, null);
    }
}
