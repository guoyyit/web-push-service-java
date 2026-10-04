package com.github.guoyyit.webpushservicejava.push.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;

public class PayloadCipher {

    public static final int VERSION = 1;
    private static final int SALT_LENGTH = 16;
    private static final int KEY_CHECK_LENGTH = 16;
    private static final int CEK_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PayloadCipher(Clock clock) {
        this.clock = clock;
    }

    public EncryptedPayload encrypt(byte[] plaintext, String subscriberPublicKeyBase64,
                                    String authSecretBase64, long ttlSeconds) {
        PublicKey subscriberPublicKey = CryptoUtils.decodeP256PublicKey(subscriberPublicKeyBase64);
        byte[] authSecret = CryptoUtils.base64UrlDecode(authSecretBase64);
        KeyPair ephemeral = VapidService.generateKeyPair();
        String ephemeralPublicKey = CryptoUtils.encodePublicKey(ephemeral.getPublic());

        byte[] sharedSecret = ecdh(ephemeral.getPrivate(), subscriberPublicKey);
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);

        byte[] ikm = CryptoUtils.hkdfExtract(authSecret, sharedSecret);
        byte[] context = context(ephemeralPublicKey, subscriberPublicKeyBase64);
        byte[] keyCheck = CryptoUtils.hkdfExpand(ikm, concat("key-check".getBytes(StandardCharsets.UTF_8), context),
                KEY_CHECK_LENGTH);
        byte[] prk = CryptoUtils.hkdfExtract(salt, ikm);
        byte[] cek = CryptoUtils.hkdfExpand(prk, concat("cek".getBytes(StandardCharsets.UTF_8), context), CEK_LENGTH);
        byte[] nonce = CryptoUtils.hkdfExpand(prk, concat("nonce".getBytes(StandardCharsets.UTF_8), context),
                NONCE_LENGTH);

        long expiresAt = clock.instant().plusSeconds(ttlSeconds).getEpochSecond();
        EncryptedPayload payload = new EncryptedPayload();
        payload.setVersion(VERSION);
        payload.setEphemeralPublicKey(ephemeralPublicKey);
        payload.setSalt(CryptoUtils.base64UrlEncode(salt));
        payload.setKeyCheck(CryptoUtils.base64UrlEncode(keyCheck));
        payload.setExpiresAtEpochSeconds(expiresAt);
        payload.setCiphertext(CryptoUtils.base64UrlEncode(aesGcm(true, cek, nonce, aad(payload), plaintext)));
        return payload;
    }

    public byte[] decrypt(EncryptedPayload payload, String subscriberPrivateKeyBase64,
                          String subscriberPublicKeyBase64,
                          String authSecretBase64) throws DecryptException {
        if (payload == null || payload.getVersion() != VERSION) {
            throw new DecryptException(DecryptFailureReason.MALFORMED, "unsupported payload version");
        }
        final PrivateKey subscriberPrivateKey;
        final PublicKey ephemeralPublicKey;
        final byte[] salt;
        final byte[] keyCheck;
        final byte[] ciphertext;
        final byte[] authSecret;
        try {
            subscriberPrivateKey = CryptoUtils.decodeP256PrivateKey(subscriberPrivateKeyBase64);
            ephemeralPublicKey = CryptoUtils.decodeP256PublicKey(payload.getEphemeralPublicKey());
            salt = CryptoUtils.base64UrlDecode(payload.getSalt());
            keyCheck = CryptoUtils.base64UrlDecode(payload.getKeyCheck());
            ciphertext = CryptoUtils.base64UrlDecode(payload.getCiphertext());
            authSecret = CryptoUtils.base64UrlDecode(authSecretBase64);
        } catch (IllegalArgumentException e) {
            throw new DecryptException(DecryptFailureReason.MALFORMED, "payload fields are not well formed", e);
        }

        byte[] sharedSecret;
        try {
            sharedSecret = ecdh(subscriberPrivateKey, ephemeralPublicKey);
        } catch (IllegalStateException e) {
            throw new DecryptException(DecryptFailureReason.KEY_MISMATCH, "ECDH key agreement failed", e);
        }
        byte[] ikm = CryptoUtils.hkdfExtract(authSecret, sharedSecret);
        byte[] context = context(payload.getEphemeralPublicKey(), subscriberPublicKeyBase64);
        byte[] expectedKeyCheck = CryptoUtils.hkdfExpand(ikm,
                concat("key-check".getBytes(StandardCharsets.UTF_8), context), KEY_CHECK_LENGTH);
        if (!MessageDigest.isEqual(expectedKeyCheck, keyCheck)) {
            throw new DecryptException(DecryptFailureReason.KEY_MISMATCH,
                    "key confirmation failed: subscriber key does not match");
        }

        byte[] prk = CryptoUtils.hkdfExtract(salt, ikm);
        byte[] cek = CryptoUtils.hkdfExpand(prk, concat("cek".getBytes(StandardCharsets.UTF_8), context), CEK_LENGTH);
        byte[] nonce = CryptoUtils.hkdfExpand(prk, concat("nonce".getBytes(StandardCharsets.UTF_8), context),
                NONCE_LENGTH);
        final byte[] plaintext;
        try {
            plaintext = aesGcm(false, cek, nonce, aad(payload), ciphertext);
        } catch (IllegalStateException e) {
            throw new DecryptException(DecryptFailureReason.TAMPERED,
                    "authenticated decryption failed: ciphertext or associated data was modified", e);
        }
        if (Instant.now(clock).getEpochSecond() > payload.getExpiresAtEpochSeconds()) {
            throw new DecryptException(DecryptFailureReason.EXPIRED,
                    "payload expired at " + Instant.ofEpochSecond(payload.getExpiresAtEpochSeconds()));
        }
        return plaintext;
    }

    private static byte[] aad(EncryptedPayload payload) {
        return ("v" + payload.getVersion() + "." + payload.getEphemeralPublicKey() + "." + payload.getSalt()
                + "." + payload.getKeyCheck() + "." + payload.getExpiresAtEpochSeconds())
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] context(String ephemeralPublicKey, String subscriberPublicKey) {
        return concat(CryptoUtils.base64UrlDecode(ephemeralPublicKey),
                CryptoUtils.base64UrlDecode(subscriberPublicKey));
    }

    private static byte[] ecdh(PrivateKey privateKey, PublicKey publicKey) {
        try {
            javax.crypto.KeyAgreement agreement = javax.crypto.KeyAgreement.getInstance("ECDH");
            agreement.init(privateKey);
            agreement.doPhase(publicKey, true);
            return agreement.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ECDH key agreement failed", e);
        }
    }

    private static byte[] aesGcm(boolean encrypt, byte[] key, byte[] nonce, byte[] aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM operation failed", e);
        }
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = new byte[left.length + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }
}
