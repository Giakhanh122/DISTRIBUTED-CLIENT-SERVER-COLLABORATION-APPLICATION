package logic;

import java.net.InetSocketAddress;

public record PeerLookupResult(
        Status status,
        InetSocketAddress address) implements ReturnTypes {

    public static enum Status {
        SUCCESS,
        USER_OFFLINE,
        ALREADY_CONNECTED
    }
}