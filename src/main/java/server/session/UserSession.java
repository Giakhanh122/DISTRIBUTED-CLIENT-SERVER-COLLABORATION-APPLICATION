package server.session;

public record UserSession (
    String username,
    String ip,
    int port
) {}
