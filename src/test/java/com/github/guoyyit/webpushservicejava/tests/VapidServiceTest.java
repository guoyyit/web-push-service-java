package com.github.guoyyit.webpushservicejava.tests;

import com.github.guoyyit.webpushservicejava.push.crypto.VapidService;
import com.github.guoyyit.webpushservicejava.push.exception.ErrorCode;
import com.github.guoyyit.webpushservicejava.push.exception.PushException;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VapidServiceTest {

    private static final String AUDIENCE = "http://localhost:8080";
    private static final String SUBJECT = "mailto:admin@localhost";

    private final VapidService vapidService = new VapidService();
    private final KeyPair keyPair = VapidService.generateKeyPair();

    @Test
    void signAndVerifyRoundTrip() {
        String token = vapidService.sign(AUDIENCE, Instant.now().plusSeconds(3600), SUBJECT, keyPair.getPrivate());
        assertDoesNotThrow(() -> vapidService.verify(token, AUDIENCE, keyPair.getPublic(), Instant.now()));
    }

    @Test
    void expiredTokenIsRejected() {
        String token = vapidService.sign(AUDIENCE, Instant.now().minusSeconds(3600), SUBJECT, keyPair.getPrivate());
        PushException exception = assertThrows(PushException.class,
                () -> vapidService.verify(token, AUDIENCE, keyPair.getPublic(), Instant.now()));
        assertEquals(ErrorCode.VAPID_EXPIRED, exception.getCode());
    }

    @Test
    void wrongAudienceIsRejected() {
        String token = vapidService.sign("https://other.example.com", Instant.now().plusSeconds(3600),
                SUBJECT, keyPair.getPrivate());
        PushException exception = assertThrows(PushException.class,
                () -> vapidService.verify(token, AUDIENCE, keyPair.getPublic(), Instant.now()));
        assertEquals(ErrorCode.VAPID_AUDIENCE_MISMATCH, exception.getCode());
    }

    @Test
    void wrongKeyIsRejected() {
        String token = vapidService.sign(AUDIENCE, Instant.now().plusSeconds(3600), SUBJECT, keyPair.getPrivate());
        KeyPair other = VapidService.generateKeyPair();
        PushException exception = assertThrows(PushException.class,
                () -> vapidService.verify(token, AUDIENCE, other.getPublic(), Instant.now()));
        assertEquals(ErrorCode.VAPID_SIGNATURE_INVALID, exception.getCode());
    }

    @Test
    void tamperedSignatureIsRejected() {
        String token = vapidService.sign(AUDIENCE, Instant.now().plusSeconds(3600), SUBJECT, keyPair.getPrivate());
        String[] parts = token.split("\\.");
        char[] signature = parts[2].toCharArray();
        signature[0] = signature[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(signature);
        PushException exception = assertThrows(PushException.class,
                () -> vapidService.verify(tampered, AUDIENCE, keyPair.getPublic(), Instant.now()));
        assertEquals(ErrorCode.VAPID_SIGNATURE_INVALID, exception.getCode());
    }

    @Test
    void tamperedClaimsAreRejected() {
        String token = vapidService.sign(AUDIENCE, Instant.now().plusSeconds(3600), SUBJECT, keyPair.getPrivate());
        String[] parts = token.split("\\.");
        String forgedClaims = com.github.guoyyit.webpushservicejava.push.crypto.CryptoUtils.base64UrlEncode(
                ("{\"aud\":\"" + AUDIENCE + "\",\"exp\":" + (Instant.now().plusSeconds(7200).getEpochSecond())
                        + ",\"sub\":\"" + SUBJECT + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedClaims + "." + parts[2];
        PushException exception = assertThrows(PushException.class,
                () -> vapidService.verify(forged, AUDIENCE, keyPair.getPublic(), Instant.now()));
        assertEquals(ErrorCode.VAPID_SIGNATURE_INVALID, exception.getCode());
    }

    @Test
    void malformedTokenIsRejected() {
        PushException exception = assertThrows(PushException.class,
                () -> vapidService.verify("not-a-jwt", AUDIENCE, keyPair.getPublic(), Instant.now()));
        assertEquals(ErrorCode.VAPID_MALFORMED, exception.getCode());
    }

    @Test
    void missingTokenIsRejected() {
        PushException exception = assertThrows(PushException.class,
                () -> vapidService.verify(null, AUDIENCE, keyPair.getPublic(), Instant.now()));
        assertEquals(ErrorCode.VAPID_MISSING, exception.getCode());
    }
}
