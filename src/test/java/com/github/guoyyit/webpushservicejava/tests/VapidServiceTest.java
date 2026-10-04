package com.github.guoyyit.webpushservicejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.webpushservicejava.config.PushProperties;
import com.github.guoyyit.webpushservicejava.domain.ErrorCode;
import com.github.guoyyit.webpushservicejava.persistence.PersistenceService;
import com.github.guoyyit.webpushservicejava.vapid.VapidService;
import com.github.guoyyit.webpushservicejava.web.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VapidServiceTest {

    private VapidService vapidService;
    private String oldPublicKey;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        PushProperties properties = new PushProperties();
        properties.setDataDir(tempDir.toString());
        PersistenceService persistenceService = new PersistenceService(properties, new ObjectMapper());
        vapidService = new VapidService(persistenceService, properties, new ObjectMapper());
        oldPublicKey = vapidService.getPublicKeyBase64();
    }

    @Test
    void validSignatureIsAccepted() {
        String authorization = vapidService.signAuthorization("http://localhost:8080", "mailto:a@b.c");

        Map<String, Object> claims = vapidService.verify(authorization, "http://localhost:8080");

        assertEquals("http://localhost:8080", claims.get("aud"));
        assertEquals("mailto:a@b.c", claims.get("sub"));
    }

    @Test
    void missingAuthorizationIsRejected() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> vapidService.verify(null, "http://localhost:8080"));
        assertEquals(ErrorCode.VAPID_MISSING, exception.getErrorCode());
    }

    @Test
    void audienceMismatchIsRejected() {
        String authorization = vapidService.signAuthorization("http://localhost:8080", null);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> vapidService.verify(authorization, "http://evil.example:9000"));

        assertEquals(ErrorCode.VAPID_AUDIENCE_MISMATCH, exception.getErrorCode());
    }

    @Test
    void expiredTokenIsRejected(@TempDir Path tempDir) {
        PushProperties properties = new PushProperties();
        properties.setDataDir(tempDir.resolve("short").toString());
        properties.setVapidTtlSeconds(-10);
        PersistenceService persistenceService = new PersistenceService(properties, new ObjectMapper());
        VapidService shortLived = new VapidService(persistenceService, properties, new ObjectMapper());

        String authorization = shortLived.signAuthorization("http://localhost:8080", null);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> shortLived.verify(authorization, "http://localhost:8080"));
        assertEquals(ErrorCode.VAPID_EXPIRED, exception.getErrorCode());
    }

    @Test
    void tamperedSignatureIsRejected() {
        String authorization = vapidService.signAuthorization("http://localhost:8080", null);
        String tampered = tamperLastSegment(authorization);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> vapidService.verify(tampered, "http://localhost:8080"));

        assertEquals(ErrorCode.VAPID_BAD_SIGNATURE, exception.getErrorCode());
    }

    @Test
    void tokenSignedBeforeRotationIsRejectedAfterwards(@TempDir Path tempDir) {
        PushProperties properties = new PushProperties();
        properties.setDataDir(tempDir.resolve("rotate").toString());
        PersistenceService persistenceService = new PersistenceService(properties, new ObjectMapper());
        VapidService service = new VapidService(persistenceService, properties, new ObjectMapper());
        String oldToken = service.signAuthorization("http://localhost:8080", null);

        service.rotate();

        assertNotEquals(oldToken, service.getPublicKeyBase64());
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.verify(oldToken, "http://localhost:8080"));
        assertEquals(ErrorCode.VAPID_BAD_SIGNATURE, exception.getErrorCode());
    }

    @Test
    void vapidKeySurvivesServiceRestart(@TempDir Path tempDir) {
        PushProperties properties = new PushProperties();
        properties.setDataDir(tempDir.resolve("restart").toString());
        PersistenceService persistenceService = new PersistenceService(properties, new ObjectMapper());
        VapidService first = new VapidService(persistenceService, properties, new ObjectMapper());
        String publicKey = first.getPublicKeyBase64();
        String token = first.signAuthorization("http://localhost:8080", null);

        VapidService restarted = new VapidService(
                new PersistenceService(properties, new ObjectMapper()),
                properties, new ObjectMapper());

        assertEquals(publicKey, restarted.getPublicKeyBase64());
        assertTrue(restarted.verify(token, "http://localhost:8080").containsKey("exp"));
    }

    private String tamperLastSegment(String authorization) {
        int tIndex = authorization.indexOf("t=") + 2;
        int comma = authorization.indexOf(',', tIndex);
        String token = authorization.substring(tIndex, comma);
        String[] parts = token.split("\\.");
        char flipped = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        String tamperedSignature = flipped + parts[2].substring(1);
        String tamperedToken = parts[0] + "." + parts[1] + "." + tamperedSignature;
        return authorization.substring(0, tIndex) + tamperedToken + authorization.substring(comma);
    }
}
