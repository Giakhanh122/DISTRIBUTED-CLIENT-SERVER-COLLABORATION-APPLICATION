package client;

/** Lifecycle of the authenticated control connection; existing P2P sockets have their own lifecycle. */
public enum ConnectionState {
    CONNECTING, ONLINE, SERVER_OFFLINE, RECONNECTING, CLOSED
}
