package server.session;

import java.net.Socket;

public record UserSession(
        String username,
        String ip,
        int port,
        Socket clientSocket) {
}
