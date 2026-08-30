package com.samsung.sure.partner.observability.testapp.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

/** Ephemeral AES-GCM helper. Its generated key exists only for one local fixture process. */
@Component
public final class SyntheticCryptoService {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_PLAINTEXT_BYTES = 1024 * 1024;

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    public SyntheticCryptoService() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            key = generator.generateKey();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SYNTHETIC_KEY_INITIALIZATION_FAILED", exception);
        }
    }

    public byte[] encrypt(byte[] plaintext) {
        if (plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("SYNTHETIC_PLAINTEXT_TOO_LARGE");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SYNTHETIC_ENCRYPTION_FAILED", exception);
        }
    }

    public byte[] decrypt(byte[] encrypted) {
        if (encrypted.length <= NONCE_BYTES || encrypted.length > MAX_PLAINTEXT_BYTES + 64) {
            throw new IllegalArgumentException("SYNTHETIC_CIPHERTEXT_SIZE_INVALID");
        }
        byte[] nonce = java.util.Arrays.copyOfRange(encrypted, 0, NONCE_BYTES);
        byte[] ciphertext = java.util.Arrays.copyOfRange(encrypted, NONCE_BYTES, encrypted.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SYNTHETIC_DECRYPTION_FAILED", exception);
        }
    }
}
