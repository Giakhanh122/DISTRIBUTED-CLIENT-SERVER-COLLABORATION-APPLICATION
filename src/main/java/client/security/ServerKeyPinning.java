package client.security;

import security.PeerKeyProof;
import security.X25519;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

public final class ServerKeyPinning {
    private static final String ALGORITHM = "Ed25519";
    private final PublicKey trustedServerKey;

    public ServerKeyPinning(Path pinnedKeyPath) throws IOException, GeneralSecurityException {
        byte[] encoded = Files.readAllBytes(pinnedKeyPath);

        KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
        trustedServerKey = factory.generatePublic(new X509EncodedKeySpec(encoded));
    }

    public boolean verify(byte[] message, byte[] signature) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance(ALGORITHM);
        verifier.initVerify(trustedServerKey);
        verifier.update(message);

        return verifier.verify(signature);
    }

    public PublicKey verifyPeerKey(PeerKeyProof proof, String expectedUsername, UUID sessionId)
            throws GeneralSecurityException {
        if (!expectedUsername.equals(proof.username()) || !sessionId.toString().equals(proof.sessionId())) {
            throw new GeneralSecurityException("Peer public key confirmation does not match connection");
        }

        try {
            byte[] signature = Base64.getDecoder().decode(proof.serverSignature());
            if (signature.length != 64 || !verify(proof.signedData(), signature)) {
                throw new GeneralSecurityException("Invalid server signature for peer public key");
            }

            return X25519.decodePublicKey(proof.publicKey());
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Invalid Base64 in peer public key confirmation", e);
        }
    }

    public PublicKey getTrustedServerKey() {
        return trustedServerKey;
    }
}
