package com.github.guoyyit.webpushservicejava.tests;

import com.github.guoyyit.webpushservicejava.push.crypto.CryptoUtils;
import com.github.guoyyit.webpushservicejava.push.crypto.VapidService;
import com.github.guoyyit.webpushservicejava.push.endpoint.EndpointRegistry;
import com.github.guoyyit.webpushservicejava.push.exception.ErrorCode;
import com.github.guoyyit.webpushservicejava.push.exception.PushException;
import com.github.guoyyit.webpushservicejava.push.model.Subscription;
import com.github.guoyyit.webpushservicejava.push.service.SubscriptionLocks;
import com.github.guoyyit.webpushservicejava.push.service.SubscriptionService;
import com.github.guoyyit.webpushservicejava.push.store.DeliveryStore;
import com.github.guoyyit.webpushservicejava.push.store.JsonFileStore;
import com.github.guoyyit.webpushservicejava.push.store.SubscriptionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionServiceTest {

    @TempDir
    Path dataDir;

    private SubscriptionService service;
    private String endpoint;
    private String p256dh;
    private String auth;

    @BeforeEach
    void setUp() {
        JsonFileStore fileStore = new JsonFileStore(dataDir);
        service = new SubscriptionService(new SubscriptionStore(fileStore), new DeliveryStore(fileStore),
                new SubscriptionLocks(), new MutableClock(Instant.now()));
        endpoint = new EndpointRegistry().register();
        KeyPair keyPair = VapidService.generateKeyPair();
        p256dh = CryptoUtils.encodePublicKey(keyPair.getPublic());
        auth = CryptoUtils.base64UrlEncode("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fullLifecycle() {
        Subscription created = service.create(endpoint, p256dh, auth);
        assertEquals(1, created.getVersion());

        Subscription fetched = service.get(created.getId());
        assertEquals(endpoint, fetched.getEndpoint());
        assertEquals(1, service.list().size());

        String newEndpoint = new EndpointRegistry().register();
        Subscription updated = service.update(created.getId(), newEndpoint, p256dh, auth);
        assertEquals(2, updated.getVersion());
        assertEquals(newEndpoint, service.get(created.getId()).getEndpoint());

        service.delete(created.getId());
        assertTrue(service.list().isEmpty());
        PushException exception = assertThrows(PushException.class, () -> service.get(created.getId()));
        assertEquals(ErrorCode.SUBSCRIPTION_NOT_FOUND, exception.getCode());
    }

    @Test
    void invalidEndpointIsRejected() {
        PushException exception = assertThrows(PushException.class,
                () -> service.create("https://evil.example.com/push", p256dh, auth));
        assertEquals(ErrorCode.INVALID_ENDPOINT, exception.getCode());
    }

    @Test
    void malformedEndpointIsRejected() {
        PushException exception = assertThrows(PushException.class,
                () -> service.create("endpoint://", p256dh, auth));
        assertEquals(ErrorCode.INVALID_ENDPOINT, exception.getCode());
    }

    @Test
    void invalidPublicKeyIsRejected() {
        PushException exception = assertThrows(PushException.class,
                () -> service.create(endpoint, "not-a-key", auth));
        assertEquals(ErrorCode.INVALID_KEY, exception.getCode());
    }

    @Test
    void truncatedPublicKeyIsRejected() {
        String truncated = CryptoUtils.base64UrlEncode(new byte[]{0x04, 0x01, 0x02});
        PushException exception = assertThrows(PushException.class,
                () -> service.create(endpoint, truncated, auth));
        assertEquals(ErrorCode.INVALID_KEY, exception.getCode());
    }

    @Test
    void shortAuthSecretIsRejected() {
        String shortAuth = CryptoUtils.base64UrlEncode(new byte[]{1, 2, 3});
        PushException exception = assertThrows(PushException.class,
                () -> service.create(endpoint, p256dh, shortAuth));
        assertEquals(ErrorCode.INVALID_KEY, exception.getCode());
    }

    @Test
    void updateOfMissingSubscriptionIsRejected() {
        PushException exception = assertThrows(PushException.class,
                () -> service.update("missing", endpoint, p256dh, auth));
        assertEquals(ErrorCode.SUBSCRIPTION_NOT_FOUND, exception.getCode());
    }

    @Test
    void deleteOfMissingSubscriptionIsRejected() {
        PushException exception = assertThrows(PushException.class, () -> service.delete("missing"));
        assertEquals(ErrorCode.SUBSCRIPTION_NOT_FOUND, exception.getCode());
    }
}
