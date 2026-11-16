package server.session;

import network.PacketChannel;

public final class UserSession {
    private final String username;
    private final String ip;
    private final int port;
    private final PacketChannel channel;
    private volatile boolean ready;

    public UserSession(String username, String ip, int port, PacketChannel channel) {
        this.username = username;
        this.ip = ip;
        this.port = port;
        this.channel = channel;
    }

    public String username() { return username; }
    public String ip() { return ip; }
    public int port() { return port; }
    public PacketChannel channel() { return channel; }
    public boolean isReady() { return ready; }
    public void activate() { ready = true; }
}
