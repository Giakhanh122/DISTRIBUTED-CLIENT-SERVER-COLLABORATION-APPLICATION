package security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public record SessionKeys(SecretKey initiatorToResponder, SecretKey responderToInitiator) {
    public static SessionKeys derive(byte[] sharedSecret, byte[] handshakeData)
            throws GeneralSecurityException {
        byte[] salt = MessageDigest.getInstance("SHA-256").digest(handshakeData);
        return deriveDirections(sharedSecret, sharedSecret, salt);
    }

    /** Fresh keys for a file socket attempt, so failed attempts need not consume the accepted offer. */
    public SessionKeys forFileConnection(byte[] nonce) throws GeneralSecurityException {
        if (nonce.length != 32) throw new GeneralSecurityException("File connection nonce must be 32 bytes");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("DLP-v2 peer-file-connection".getBytes(StandardCharsets.US_ASCII));
        byte[] salt = digest.digest(nonce);
        byte[] forwardSecret = initiatorToResponder.getEncoded();
        byte[] reverseSecret = responderToInitiator.getEncoded();
        try {
            return deriveDirections(forwardSecret, reverseSecret, salt);
        } finally {
            Arrays.fill(forwardSecret, (byte) 0);
            Arrays.fill(reverseSecret, (byte) 0);
        }
    }

    private static SessionKeys deriveDirections(byte[] forwardSecret, byte[] reverseSecret, byte[] salt)
            throws GeneralSecurityException {
        // Versioned direction labels also apply to P2P initiators/responders.
        byte[] forward = Hkdf.derive(forwardSecret, salt,
                "DLP-v2 client-to-server AES-256-GCM".getBytes(StandardCharsets.US_ASCII), 32);
        byte[] reverse = null;
        try {
            reverse = Hkdf.derive(reverseSecret, salt,
                    "DLP-v2 server-to-client AES-256-GCM".getBytes(StandardCharsets.US_ASCII), 32);
            return new SessionKeys(new SecretKeySpec(forward, "AES"), new SecretKeySpec(reverse, "AES"));
        } finally {
            Arrays.fill(forward, (byte) 0);
            if (reverse != null) Arrays.fill(reverse, (byte) 0);
        }
    }
}
