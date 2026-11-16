package client.console;

import client.ClientEvents;
import client.transfer.FileOffer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Console presentation of asynchronous events; network classes do not format CLI output. */
final class ConsoleEvents implements ClientEvents {
    private final Map<String, FileOffer> offers = new ConcurrentHashMap<>();

    @Override public void onSystemMessage(String content) { System.out.println(content); }

    @Override public void onMessage(String peer, String content) { System.out.println(peer + ": " + content); }
    @Override public void onMessageDelivered(String peer, String messageId) {
        System.out.println("[DELIVERED] Message to " + peer + " was delivered.");
    }
    @Override public void onTyping(String peer, boolean typing) {
        System.out.println(typing ? peer + " is typing..." : peer + " stopped typing.");
    }
    @Override public void onPresenceUpdate(String username, boolean online) {
        System.out.println("[PRESENCE] " + username + (online ? " is online." : " is offline."));
    }
    @Override public void onProtocolError(String code, String reason, boolean fatal) {
        System.err.println("[PROTOCOL " + code + (fatal ? ", fatal" : "") + "] " + reason);
    }

    @Override public void onBroadcast(String communityId, String sender, String content) {
        System.out.println("(" + communityId + ":broadcast) " + sender + " : " + content);
    }
    @Override public void onGlobalBroadcast(String sender, String content) {
        System.out.println("[GLOBAL] " + sender + ": " + content);
    }

    @Override public void onPeerConnected(String peer) { System.out.println("[P2P] Connected to " + peer); }
    @Override public void onPeerDisconnected(String peer) { System.out.println("[P2P] Disconnected from " + peer); }

    @Override public void onServerClosed() {
        System.out.println("Server disconnected. Existing P2P connections remain available; use /reconnect.");
    }

    @Override public void onServerReconnected() {
        System.out.println("Server reconnected. Use /communities to refresh and select a community.");
    }

    @Override public void onFileOffer(FileOffer offer) {
        offers.put(offer.id(), offer);
        var meta = offer.meta();
        if (offer.incoming()) {
            String kind = offer.communityId() == null ? "FILE OFFER" : "BROADCAST FILE OFFER";
            System.out.println("[" + kind + " #" + offer.number() + "] " + meta.sender()
                    + " offers '" + meta.fileName() + "' (" + meta.fileSize() + " bytes).");
            System.out.println("SHA-256: " + meta.checksum());
            System.out.println("Type '/accept " + offer.number() + "' or '/reject " + offer.number() + "'");
        } else System.out.println("[FILE] Offering '" + meta.fileName() + "' to " + meta.receiver());
    }

    @Override public void onFileState(String id, FileState state, String detail) {
        FileOffer offer = state == FileState.TRANSFERRING ? offers.get(id) : offers.remove(id);
        String name = offer == null ? id : offer.meta().fileName();
        String message = "[FILE " + state + "] " + name + (detail == null ? "" : " — " + detail);
        if (state == FileState.FAILED) System.err.println(message);
        else System.out.println(message);
    }
}
