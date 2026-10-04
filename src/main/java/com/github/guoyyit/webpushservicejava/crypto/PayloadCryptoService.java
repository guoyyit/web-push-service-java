package com.github.guoyyit.webpushservicejava.crypto;

import com.github.guoyyit.webpushservicejava.domain.EncryptedPayload;
import com.github.guoyyit.webpushservicejava.domain.ErrorCode;
import com.github.guoyyit.webpushservicejava.domain.Subscription;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@org.springframework.stereotype.Service
public class PayloadCryptoService {

    private static final int RECORD_SIZE = 4096;
    private static final int SALT_LENGTH = 16;
    private static final int KEY_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int MAX_PAYLOAD_BYTES = 3500;
    private static final byte[] KEY_INFO_PREFIX = "Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO_PREFIX = "Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AUTH_INFO = "WebPush: info\u0000".getBytes(StandardCharsets.US_ASCII);

    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptedPayload encrypt(Subscription subscription, String plaintext, String associatedData,
                                    long payloadExpiresAtEpochMillis) {
        byte[] subscriberPublic = base64Decode(subscription.getP256dh());
        byte[] authSecret = base64Decode(subscription.getAuth());
        PublicKey uaPublic = EcKeyUtils.decodeUncompressedPublic(subscriberPublic);

        KeyPair ephemeral = EcKeyUtils.generateP256KeyPair();
        byte[] ephemeralPublic = EcKeyUtils.encodeUncompressedPublic(ephemeral.getPublic());
        byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);

        byte[] ecdhSecret = ecdh(ephemeral.getPrivate(), uaPublic);
        byte[] key = deriveKeyingMaterial(ecdhSecret, authSecret, subscriberPublic, ephemeralPublic,
                salt, KEY_INFO_PREFIX, KEY_LENGTH);
        byte[] nonce = deriveKeyingMaterial(ecdhSecret, authSecret, subscriberPublic, ephemeralPublic,
                salt, NONCE_INFO_PREFIX, NONCE_LENGTH);

        byte[] innerPlaintext = buildInnerPlaintext(plaintext, payloadExpiresAtEpochMillis);
        byte[] aad = buildAad(salt, ephemeralPublic, subscriberPublic, associatedData, 0L);

        byte[] ciphertext = aesGcm(key, nonce, aad, innerPlaintext, Cipher.ENCRYPT_MODE);

        return new EncryptedPayload(
                Base64.getUrlEncoder().withoutPadding().encodeToString(salt),
                Base64.getUrlEncoder().withoutPadding().encodeToString(ephemeralPublic),
                recipientKeyId(subscriberPublic),
                Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext),
                associatedData);
    }

    public String decrypt(EncryptedPayload payload,
                          java.security.KeyPair subscriberKeyPair,
                          byte[] subscriberAuthSecret,
                          String expectedAssociatedData) {
        byte[] subscriberPublic = EcKeyUtils.encodeUncompressedPublic(subscriberKeyPair.getPublic());
        String expectedKeyId = recipientKeyId(subscriberPublic);
        if (!expectedKeyId.equals(payload.getRecipientKeyId())) {
            throw new CryptoException(ErrorCode.DECRYPT_KEY_MISMATCH,
                    "Payload was encrypted for a different subscriber key");
        }

        byte[] salt = base64Decode(payload.getSalt());
        if (salt.length != SALT_LENGTH) {
            throw new CryptoException(ErrorCode.INVALID_ARGUMENT, "Salt must be 16 bytes");
        }
        byte[] ephemeralPublicBytes;
        try {
            ephemeralPublicBytes = base64Decode(payload.getServerPublicKey());
        } catch (IllegalArgumentException e) {
            throw new CryptoException(ErrorCode.INVALID_ARGUMENT, "Invalid server public key encoding", e);
        }
        PublicKey ephemeralPublic;
        try {
            ephemeralPublic = EcKeyUtils.decodeUncompressedPublic(ephemeralPublicBytes);
        } catch (IllegalArgumentException e) {
            throw new CryptoException(ErrorCode.DECRYPT_TAMPERED, "Server public key is not a valid P-256 point", e);
        }

        byte[] ecdhSecret = ecdh(subscriberKeyPair.getPrivate(), ephemeralPublic);
        byte[] key = deriveKeyingMaterial(ecdhSecret, subscriberAuthSecret, subscriberPublic,
                ephemeralPublicBytes, salt, KEY_INFO_PREFIX, KEY_LENGTH);
        byte[] nonce = deriveKeyingMaterial(ecdhSecret, subscriberAuthSecret, subscriberPublic,
                ephemeralPublicBytes, salt, NONCE_INFO_PREFIX, NONCE_LENGTH);

        byte[] aad = buildAad(salt, ephemeralPublicBytes, subscriberPublic,
                expectedAssociatedData, 0L);

        byte[] ciphertext;
        try {
            ciphertext = base64Decode(payload.getCiphertext());
        } catch (IllegalArgumentException e) {
            throw new CryptoException(ErrorCode.DECRYPT_TAMPERED, "Ciphertext is not valid base64", e);
        }

        byte[] innerPlaintext = aesGcm(key, nonce, aad, ciphertext, Cipher.DECRYPT_MODE);

        return parseInnerPlaintext(innerPlaintext);
    }

    public String recipientKeyId(byte[] uncompressedPublicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(uncompressedPublicKey);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private byte[] buildInnerPlaintext(String plaintext, long expiresAtEpochMillis) {
        byte[] content = plaintext.getBytes(StandardCharsets.UTF_8);
        if (content.length > MAX_PAYLOAD_BYTES) {
            throw new CryptoException(ErrorCode.INVALID_ARGUMENT,
                    "Payload exceeds maximum supported size of " + MAX_PAYLOAD_BYTES + " bytes");
        }
        return ByteBuffer.allocate(8 + content.length + 1)
                .putLong(expiresAtEpochMillis)
                .put(content)
                .put((byte) 0x02)
                .array();
    }

    private String parseInnerPlaintext(byte[] innerPlaintext) {
        if (innerPlaintext.length < 9 || innerPlaintext[innerPlaintext.length - 1] != 0x02) {
            throw new CryptoException(ErrorCode.DECRYPT_TAMPERED, "Inner plaintext record delimiter is invalid");
        }
        long expiresAt = ByteBuffer.wrap(innerPlaintext, 0, 8).getLong();
        if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
            throw new CryptoException(ErrorCode.PAYLOAD_EXPIRED,
                    "Payload expired at " + java.time.Instant.ofEpochMilli(expiresAt));
        }
        return new String(Arrays.copyOfRange(innerPlaintext, 8, innerPlaintext.length - 1),
                StandardCharsets.UTF_8);
    }

    private byte[] buildAad(byte[] salt, byte[] ephemeralPublic, byte[] subscriberPublic,
                            String associatedData, long recordSequenceNumber) {
        ByteArrayOutputStream aad = new ByteArrayOutputStream();
        aad.write(salt, 0, salt.length);
        aad.write(ephemeralPublic, 0, ephemeralPublic.length);
        aad.write((RECORD_SIZE >>> 24) & 0xFF);
        aad.write((RECORD_SIZE >>> 16) & 0xFF);
        aad.write((RECORD_SIZE >>> 8) & 0xFF);
        aad.write(RECORD_SIZE & 0xFF);
        aad.write((int) recordSequenceNumber >>> 24);
        aad.write((int) recordSequenceNumber >>> 16);
        aad.write((int) recordSequenceNumber >>> 8);
        aad.write((int) recordSequenceNumber);
        byte[] extra = associatedData == null ? new byte[0]
                : associatedData.getBytes(StandardCharsets.UTF_8);
        aad.write((extra.length >>> 8) & 0xFF);
        aad.write(extra.length & 0xFF);
        aad.write(extra, 0, extra.length);
        aad.write(subscriberPublic, 0, subscriberPublic.length);
        return aad.toByteArray();
    }

    private byte[] deriveKeyingMaterial(byte[] ecdhSecret, byte[] authSecret, byte[] subscriberPublic,
                                        byte[] ephemeralPublic, byte[] salt, byte[] infoPrefix, int length) {
        // RFC 8291 section 3.3: first HKDF uses the auth secret as salt.
        byte[] authInfo = concat(AUTH_INFO, subscriberPublic, ephemeralPublic);
        byte[] prkKey = hkdfExtract(authSecret, ecdhSecret);
        byte[] intermediate = hkdfExpand(prkKey, authInfo, 32);
        // RFC 8188 section 2.2: second HKDF uses the record salt and IKM from step one.
        byte[] contentPrk = hkdfExtract(salt, intermediate);
        return hkdfExpand(contentPrk, withInfoTail(infoPrefix), length);
    }

    private byte[] withInfoTail(byte[] prefix) {
        byte[] tail = new byte[prefix.length + 1];
        System.arraycopy(prefix, 0, tail, 0, prefix.length);
        tail[prefix.length] = 0x00;
        return tail;
    }

    private byte[] ecdh(PrivateKey privateKey, PublicKey publicKey) {
        try {
            javax.crypto.KeyAgreement agreement = javax.crypto.KeyAgreement.getInstance("ECDH");
            agreement.init(privateKey);
            agreement.doPhase(publicKey, true);
            return agreement.generateSecret();
        } catch (Exception e) {
            throw new CryptoException(ErrorCode.DECRYPT_KEY_MISMATCH, "ECDH key agreement failed", e);
        }
    }

    private byte[] hkdfExtract(byte[] salt, byte[] inputKeyingMaterial) {
        return hmac((salt == null || salt.length == 0) ? new byte[32] : salt, inputKeyingMaterial);
    }

    private byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] previous = new byte[0];
            int generated = 0;
            int counter = 1;
            while (generated < length) {
                mac.update(previous);
                mac.update(info);
                mac.update((byte) counter);
                previous = mac.doFinal();
                output.write(previous);
                generated += previous.length;
                counter++;
            }
            return Arrays.copyOf(output.toByteArray(), length);
        } catch (Exception e) {
            throw new IllegalStateException("HKDF-Expand failed", e);
        }
    }

    private byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }

    private byte[] aesGcm(byte[] key, byte[] nonce, byte[] aad, byte[] input, int mode) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(aad);
            return mode == Cipher.ENCRYPT_MODE ? cipher.doFinal(input) : cipher.doFinal(input);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new CryptoException(ErrorCode.DECRYPT_TAMPERED,
                    "Authentication tag verification failed: ciphertext or associated data was modified", e);
        } catch (Exception e) {
            throw new CryptoException(ErrorCode.INVALID_ARGUMENT, "AES-GCM operation failed", e);
        }
    }

    private byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    static byte[] base64Decode(String value) {
        if (value == null || value.isBlank()) {
            throw new CryptoException(ErrorCode.INVALID_ARGUMENT, "Base64 value is missing");
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new CryptoException(ErrorCode.INVALID_ARGUMENT, "Value is not valid base64url");
        }
    }
}
