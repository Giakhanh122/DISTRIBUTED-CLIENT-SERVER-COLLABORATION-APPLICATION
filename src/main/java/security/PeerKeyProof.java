package security;

import protocols.Json;
import protocols.Packet;

public record PeerKeyProof(String username, String sessionId, String publicKey, String serverSignature) {
    public byte[] signedData() {
        return create(username, sessionId, publicKey);
    }

    public static byte[] create(String username, String sessionId, String publicKey) {
        return Json.bytes(Json.object("purpose", "peer-public-key",
                "protocolVersion", Packet.PROTOCOL_VERSION,
                "username", username, "sessionId", sessionId, "publicKey", publicKey));
    }
}
