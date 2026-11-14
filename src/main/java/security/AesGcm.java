package security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AesGcm {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private AesGcm() {}

    public static byte[] encrypt(SecretKey key, long counter, byte[] plaintext)
            throws GeneralSecurityException {
        return crypt(Cipher.ENCRYPT_MODE, key, counter, plaintext);
    }

    public static byte[] decrypt(SecretKey key, long counter, byte[] ciphertext)
            throws GeneralSecurityException {
        return crypt(Cipher.DECRYPT_MODE, key, counter, ciphertext);
    }

    private static byte[] crypt(int mode, SecretKey key, long counter, byte[] input)
            throws GeneralSecurityException {
        if (counter < 0) throw new IllegalArgumentException("Counter must not be negative");
        byte[] nonce = ByteBuffer.allocate(NONCE_LENGTH)
                .putInt(0).putLong(counter).array();
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
        return cipher.doFinal(input);
    }
}
