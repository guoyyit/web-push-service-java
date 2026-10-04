package com.github.guoyyit.webpushservicejava.tests;

import com.github.guoyyit.webpushservicejava.crypto.CryptoException;
import com.github.guoyyit.webpushservicejava.crypto.EcKeyUtils;
import com.github.guoyyit.webpushservicejava.crypto.PayloadCryptoService;
import com.github.guoyyit.webpushservicejava.domain.EncryptedPayload;
import com.github.guoyyit.webpushservicejava.domain.ErrorCode;
import com.github.guoyyit.webpushservicejava.domain.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayloadCryptoServiceTest {

    private PayloadCryptoService cryptoService;
    private KeyPair subscriberKeyPair;
    private byte[] authSecret;
    private Subscription subscription;

    @BeforeEach
    void setUp() {
        cryptoService = new PayloadCryptoService();
        subscriberKeyPair = EcKeyUtils.generateP256KeyPair();
        authSecret = new byte[16];
        new java.security.SecureRandom().nextBytes(authSecret);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        subscription = new Subscription(
                "sub-1",
                "http://localhost:8080/push/abc",
                encoder.encodeToString(EcKeyUtils.encodeUncompressedPublic(subscriberKeyPair.getPublic())),
                encoder.encodeToString(authSecret),
                Instant.now(), Instant.now());
    }

    @Test
    void encryptThenDecryptReturnsOriginalPlaintext() {
        EncryptedPayload payload = cryptoService.encrypt(subscription, "hello push",
                "push-message:d1", System.currentTimeMillis() + 60_000);

        String plaintext = cryptoService.decrypt(payload, subscriberKeyPair, authSecret,
                "push-message:d1");

        assertEquals("hello push", plaintext);
    }

    @Test
    void repeatedEncryptionProducesDifferentCiphertexts() {
        long expiry = System.currentTimeMillis() + 60_000;
        EncryptedPayload first = cryptoService.encrypt(subscription, "same", "push-message:a", expiry);
        EncryptedPayload second = cryptoService.encrypt(subscription, "same", "push-message:a", expiry);

        assertNotEquals(first.getCiphertext(), second.getCiphertext());
        assertNotEquals(first.getSalt(), second.getSalt());
        assertNotEquals(first.getServerPublicKey(), second.getServerPublicKey());
    }

    @Test
    void decryptWithDifferentSubscriberKeyIsRejectedAsKeyMismatch() {
        EncryptedPayload payload = cryptoService.encrypt(subscription, "secret",
                "push-message:a", System.currentTimeMillis() + 60_000);
        KeyPair otherKeyPair = EcKeyUtils.generateP256KeyPair();

        CryptoException exception = assertThrows(CryptoException.class,
                () -> cryptoService.decrypt(payload, otherKeyPair, authSecret, "push-message:a"));

        assertEquals(ErrorCode.DECRYPT_KEY_MISMATCH, exception.getErrorCode());
    }

    @Test
    void tamperedCiphertextFailsAuthentication() {
        EncryptedPayload payload = cryptoService.encrypt(subscription, "secret",
                "push-message:a", System.currentTimeMillis() + 60_000);
        byte[] ciphertext = Base64.getUrlDecoder().decode(payload.getCiphertext());
        ciphertext[20] ^= 0x01;
        payload.setCiphertext(Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext));

        CryptoException exception = assertThrows(CryptoException.class,
                () -> cryptoService.decrypt(payload, subscriberKeyPair, authSecret, "push-message:a"));

        assertEquals(ErrorCode.DECRYPT_TAMPERED, exception.getErrorCode());
    }

    @Test
    void tamperedAssociatedDataFailsAuthentication() {
        EncryptedPayload payload = cryptoService.encrypt(subscription, "secret",
                "push-message:a", System.currentTimeMillis() + 60_000);

        CryptoException exception = assertThrows(CryptoException.class,
                () -> cryptoService.decrypt(payload, subscriberKeyPair, authSecret, "push-message:b"));

        assertEquals(ErrorCode.DECRYPT_TAMPERED, exception.getErrorCode());
    }

    @Test
    void expiredPayloadIsRejectedWithDistinctReason() {
        EncryptedPayload payload = cryptoService.encrypt(subscription, "stale",
                "push-message:a", System.currentTimeMillis() - 1000);

        CryptoException exception = assertThrows(CryptoException.class,
                () -> cryptoService.decrypt(payload, subscriberKeyPair, authSecret, "push-message:a"));

        assertEquals(ErrorCode.PAYLOAD_EXPIRED, exception.getErrorCode());
    }

    @Test
    void supportsUnicodePayload() {
        String plaintext = "你好，推送🔔";
        EncryptedPayload payload = cryptoService.encrypt(subscription, plaintext,
                "push-message:a", System.currentTimeMillis() + 60_000);

        String decrypted = cryptoService.decrypt(payload, subscriberKeyPair, authSecret,
                "push-message:a");

        assertEquals(plaintext, decrypted);
        assertEquals(plaintext, new String(plaintext.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
    }

    @Test
    void roundtripSurvivesJsonSerialization() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        EncryptedPayload payload = cryptoService.encrypt(subscription, "json-check",
                "push-message:z", System.currentTimeMillis() + 60_000);
        String json = mapper.writeValueAsString(payload);
        EncryptedPayload reloaded = mapper.readValue(json, EncryptedPayload.class);

        String plaintext = cryptoService.decrypt(reloaded, subscriberKeyPair, authSecret,
                "push-message:z");

        assertEquals("json-check", plaintext);
    }
}
