package com.github.guoyyit.webpushservicejava.tests;

import com.github.guoyyit.webpushservicejava.push.crypto.CryptoUtils;
import com.github.guoyyit.webpushservicejava.push.crypto.DecryptException;
import com.github.guoyyit.webpushservicejava.push.crypto.DecryptFailureReason;
import com.github.guoyyit.webpushservicejava.push.crypto.EncryptedPayload;
import com.github.guoyyit.webpushservicejava.push.crypto.PayloadCipher;
import com.github.guoyyit.webpushservicejava.push.crypto.VapidService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayloadCipherTest {

    private final MutableClock clock = new MutableClock(Instant.now());
    private final PayloadCipher cipher = new PayloadCipher(clock);
    private final KeyPair subscriberKeys = VapidService.generateKeyPair();
    private final String subscriberPublicKey = CryptoUtils.encodePublicKey(subscriberKeys.getPublic());
    private final String subscriberPrivateKey = CryptoUtils.encodePrivateKey(subscriberKeys.getPrivate());
    private final String authSecret = CryptoUtils.base64UrlEncode("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void encryptDecryptRoundTrip() throws Exception {
        byte[] plaintext = "hello push".getBytes(StandardCharsets.UTF_8);
        EncryptedPayload encrypted = cipher.encrypt(plaintext, subscriberPublicKey, authSecret, 3600);
        byte[] decrypted = cipher.decrypt(encrypted, subscriberPrivateKey, subscriberPublicKey, authSecret);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void repeatedEncryptionProducesDifferentCiphertext() {
        byte[] plaintext = "same payload".getBytes(StandardCharsets.UTF_8);
        EncryptedPayload first = cipher.encrypt(plaintext, subscriberPublicKey, authSecret, 3600);
        EncryptedPayload second = cipher.encrypt(plaintext, subscriberPublicKey, authSecret, 3600);
        assertNotEquals(first.getCiphertext(), second.getCiphertext());
        assertNotEquals(first.getSalt(), second.getSalt());
        assertNotEquals(first.getEphemeralPublicKey(), second.getEphemeralPublicKey());
    }

    @Test
    void tamperedCiphertextIsDetected() {
        EncryptedPayload encrypted = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8),
                subscriberPublicKey, authSecret, 3600);
        byte[] tampered = CryptoUtils.base64UrlDecode(encrypted.getCiphertext());
        tampered[0] ^= 0x01;
        encrypted.setCiphertext(CryptoUtils.base64UrlEncode(tampered));
        DecryptException exception = assertThrows(DecryptException.class,
                () -> cipher.decrypt(encrypted, subscriberPrivateKey, subscriberPublicKey, authSecret));
        assertEquals(DecryptFailureReason.TAMPERED, exception.getReason());
    }

    @Test
    void tamperedAssociatedDataIsDetected() {
        EncryptedPayload encrypted = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8),
                subscriberPublicKey, authSecret, 3600);
        encrypted.setExpiresAtEpochSeconds(encrypted.getExpiresAtEpochSeconds() + 10000);
        DecryptException exception = assertThrows(DecryptException.class,
                () -> cipher.decrypt(encrypted, subscriberPrivateKey, subscriberPublicKey, authSecret));
        assertEquals(DecryptFailureReason.TAMPERED, exception.getReason());
    }

    @Test
    void wrongPrivateKeyIsDistinguished() {
        EncryptedPayload encrypted = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8),
                subscriberPublicKey, authSecret, 3600);
        KeyPair otherKeys = VapidService.generateKeyPair();
        String otherPrivate = CryptoUtils.encodePrivateKey(otherKeys.getPrivate());
        String otherPublic = CryptoUtils.encodePublicKey(otherKeys.getPublic());
        DecryptException exception = assertThrows(DecryptException.class,
                () -> cipher.decrypt(encrypted, otherPrivate, otherPublic, authSecret));
        assertEquals(DecryptFailureReason.KEY_MISMATCH, exception.getReason());
    }

    @Test
    void expiredPayloadIsDistinguished() {
        EncryptedPayload encrypted = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8),
                subscriberPublicKey, authSecret, 60);
        clock.advanceSeconds(120);
        DecryptException exception = assertThrows(DecryptException.class,
                () -> cipher.decrypt(encrypted, subscriberPrivateKey, subscriberPublicKey, authSecret));
        assertEquals(DecryptFailureReason.EXPIRED, exception.getReason());
    }

    @Test
    void malformedPayloadIsDistinguished() {
        EncryptedPayload encrypted = new EncryptedPayload();
        encrypted.setVersion(99);
        DecryptException exception = assertThrows(DecryptException.class,
                () -> cipher.decrypt(encrypted, subscriberPrivateKey, subscriberPublicKey, authSecret));
        assertEquals(DecryptFailureReason.MALFORMED, exception.getReason());
    }
}
