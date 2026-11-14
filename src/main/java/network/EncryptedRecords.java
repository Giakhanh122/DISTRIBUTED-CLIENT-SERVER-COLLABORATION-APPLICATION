package network;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Objects;

import javax.crypto.SecretKey;

import protocols.PacketCodec;
import security.AesGcm;

final class EncryptedRecords {
    private static final int TAG_BYTES = 16;
    private static final int MAX_PLAINTEXT = PacketCodec.MAX_JSON_LENGTH + Integer.BYTES;
    private static final long MAX_RECORDS = 1L << 20;

    private final DataInputStream input;
    private final OutputStream output;
    private final SecretKey sendKey;
    private final SecretKey receiveKey;
    private long sendCounter;
    private long receiveCounter;

    EncryptedRecords(InputStream input, OutputStream output, SecretKey sendKey, SecretKey receiveKey) {
        this.input = new DataInputStream(Objects.requireNonNull(input));
        this.output = Objects.requireNonNull(output);
        this.sendKey = Objects.requireNonNull(sendKey);
        this.receiveKey = Objects.requireNonNull(receiveKey);
    }

    void write(byte[] plaintext) throws IOException {
        if (plaintext.length > MAX_PLAINTEXT) throw new IOException("Encrypted record exceeds size limit");
        if (sendCounter >= MAX_RECORDS) throw new IOException("Session record limit reached; reconnect required");
        try {
            byte[] ciphertext = AesGcm.encrypt(sendKey, sendCounter, plaintext);
            // Consume the nonce before writing; a failed write must never lead to reuse.
            sendCounter++;
            byte[] frame = ByteBuffer.allocate(Integer.BYTES + ciphertext.length)
                    .putInt(ciphertext.length).put(ciphertext).array();
            output.write(frame);
            output.flush();
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot encrypt record", e);
        }
    }

    byte[] read() throws IOException {
        byte[] prefix = input.readNBytes(Integer.BYTES);
        if (prefix.length == 0) return null;
        if (prefix.length != Integer.BYTES) throw new EOFException("Incomplete encrypted record length");
        int length = ByteBuffer.wrap(prefix).getInt();
        if (length < TAG_BYTES || length > MAX_PLAINTEXT + TAG_BYTES) {
            throw new IOException("Invalid encrypted record length: " + length);
        }
        if (receiveCounter >= MAX_RECORDS) throw new IOException("Session record limit reached; reconnect required");
        byte[] ciphertext = new byte[length];
        try {
            input.readFully(ciphertext);
        } catch (EOFException e) {
            EOFException failure = new EOFException("Incomplete encrypted record payload");
            failure.initCause(e);
            throw failure;
        }
        try {
            byte[] plaintext = AesGcm.decrypt(receiveKey, receiveCounter, ciphertext);
            receiveCounter++;
            return plaintext;
        } catch (GeneralSecurityException e) {
            throw new IOException("Encrypted record authentication failed", e);
        }
    }
}
