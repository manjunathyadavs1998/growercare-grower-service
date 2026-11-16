package com.growercare.grower.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Protects phone numbers at rest (SPEC-GRW-01 D3, PRV-5):
 * <ul>
 *   <li>{@link #hmac}: HMAC-SHA-256 blind index for exact lookups (a plain hash of a 10-digit number is
 *       brute-forceable; a keyed one is not without the key);</li>
 *   <li>{@link #encrypt}: AES-256-GCM with a random 96-bit nonce ({@code nonce ‖ ciphertext ‖ tag}), tagged with the key
 *       id so keys can rotate; only the audited unmask (SPEC-GRW-04) decrypts.</li>
 * </ul>
 * Uses only the JDK, so it stays in the framework-free domain.
 */
public final class PhoneProtector {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec hmacKey;
    private final SecretKeySpec encryptionKey;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    /** Ciphertext ({@code nonce ‖ ciphertext ‖ tag}) and the id of the key that sealed it. */
    public record Sealed(byte[] ciphertext, String keyId) {
    }

    public PhoneProtector(byte[] hmacKey, byte[] encryptionKey, String keyId) {
        this.hmacKey = new SecretKeySpec(requireKey(hmacKey, "HMAC"), "HmacSHA256");
        this.encryptionKey = new SecretKeySpec(requireKey(encryptionKey, "encryption"), "AES");
        this.keyId = Objects.requireNonNull(keyId, "keyId");
    }

    /** Keys as configured: base64, 32 bytes each. */
    public static PhoneProtector fromBase64(String hmacKey, String encryptionKey, String keyId) {
        var decoder = Base64.getDecoder();
        return new PhoneProtector(decoder.decode(hmacKey), decoder.decode(encryptionKey), keyId);
    }

    private static byte[] requireKey(byte[] key, String purpose) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("the phone " + purpose + " key must be " + KEY_BYTES + " bytes");
        }
        return key.clone();
    }

    public byte[] hmac(PhoneNumber phone) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return mac.doFinal(phone.e164().getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 unavailable", e);
        }
    }

    public Sealed encrypt(PhoneNumber phone) {
        var nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            var sealed = cipher.doFinal(phone.e164().getBytes(StandardCharsets.US_ASCII));
            return new Sealed(ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array(), keyId);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    /** @throws IllegalArgumentException when the ciphertext was tampered with or sealed with another key */
    public String decrypt(Sealed sealed) {
        var bytes = sealed.ciphertext();
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, Arrays.copyOf(bytes, NONCE_BYTES)));
            return new String(cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES), StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("phone ciphertext rejected (tampered or other key)", e);
        }
    }
}
