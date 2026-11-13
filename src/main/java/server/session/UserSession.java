package server.session;

import network.PacketChannel;

public record UserSession(
        String username,
        String ip,
        int port,
        PacketChannel channel) {
}
