package client;

import java.net.InetSocketAddress;

public record PeerLookupResult(
        Status status,
        InetSocketAddress address) {

    public enum Status {
        SUCCESS,
        USER_OFFLINE,
        ALREADY_CONNECTED
    }
}
