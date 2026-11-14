package security;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Hkdf {
    private static final String ALGORITHM = "HmacSHA256";
    private static final int HASH_LENGTH = 32;

    private Hkdf() {}

    public static byte[] derive(byte[] sharedSecret, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        Objects.requireNonNull(sharedSecret, "sharedSecret");
        Objects.requireNonNull(salt, "salt");
        Objects.requireNonNull(info, "info");
        if (length < 1 || length > 255 * HASH_LENGTH) {
            throw new IllegalArgumentException("Derived key length must be between 1 and 8160 bytes");
        }

        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[HASH_LENGTH] : salt, ALGORITHM));
        byte[] pseudorandomKey = mac.doFinal(sharedSecret);
        byte[] previousBlock = new byte[0];
        try {
            mac.init(new SecretKeySpec(pseudorandomKey, ALGORITHM));
            byte[] result = new byte[length];
            int offset = 0;
            for (int counter = 1; offset < length; counter++) {
                mac.update(previousBlock);
                mac.update(info);
                mac.update((byte) counter);
                byte[] nextBlock = mac.doFinal();
                Arrays.fill(previousBlock, (byte) 0);
                previousBlock = nextBlock;

                int size = Math.min(previousBlock.length, length - offset);
                System.arraycopy(previousBlock, 0, result, offset, size);
                offset += size;
            }
            return result;
        } finally {
            Arrays.fill(pseudorandomKey, (byte) 0);
            Arrays.fill(previousBlock, (byte) 0);
        }
    }
}
