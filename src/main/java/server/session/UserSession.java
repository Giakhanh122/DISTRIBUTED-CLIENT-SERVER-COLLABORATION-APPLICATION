package server.session;

import protocols.PacketChannel;

public record UserSession(
        String username,
        String ip,
        int port,
        PacketChannel channel) {
}
