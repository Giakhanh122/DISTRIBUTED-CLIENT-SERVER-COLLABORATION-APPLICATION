package security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.KeyAgreement;

/** Shared X25519 operations; callers supply the authenticated peer key and handshake transcript. */
public final class X25519 {
    private static final String ALGORITHM = "X25519";

    private X25519() {}

    public static KeyPair createKeyPair() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
    }

    public static PublicKey decodePublicKey(String publicKey) throws GeneralSecurityException {
        byte[] encoded = Base64.getDecoder().decode(publicKey);
        return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(encoded));
    }

    public static SessionKeys deriveSessionKeys(KeyPair keyPair, PublicKey peerPublicKey, byte[] handshakeData)
            throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance(ALGORITHM);
        agreement.init(keyPair.getPrivate());
        agreement.doPhase(peerPublicKey, true);
        byte[] sharedSecret = agreement.generateSecret();
        try {
            if (MessageDigest.isEqual(sharedSecret, new byte[32])) {
                throw new GeneralSecurityException("Invalid X25519 shared secret");
            }
            return SessionKeys.derive(sharedSecret, handshakeData);
        } finally {
            Arrays.fill(sharedSecret, (byte) 0);
        }
    }
}
