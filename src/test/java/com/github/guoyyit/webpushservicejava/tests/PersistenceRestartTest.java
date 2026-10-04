package com.github.guoyyit.webpushservicejava.tests;

import com.github.guoyyit.webpushservicejava.push.config.PushProperties;
import com.github.guoyyit.webpushservicejava.push.crypto.CryptoUtils;
import com.github.guoyyit.webpushservicejava.push.crypto.PayloadCipher;
import com.github.guoyyit.webpushservicejava.push.crypto.VapidService;
import com.github.guoyyit.webpushservicejava.push.endpoint.EndpointRegistry;
import com.github.guoyyit.webpushservicejava.push.exception.ErrorCode;
import com.github.guoyyit.webpushservicejava.push.exception.PushException;
import com.github.guoyyit.webpushservicejava.push.model.PushMessage;
import com.github.guoyyit.webpushservicejava.push.model.Subscription;
import com.github.guoyyit.webpushservicejava.push.service.DeliveryService;
import com.github.guoyyit.webpushservicejava.push.service.SubscriptionLocks;
import com.github.guoyyit.webpushservicejava.push.store.DeliveryStore;
import com.github.guoyyit.webpushservicejava.push.store.JsonFileStore;
import com.github.guoyyit.webpushservicejava.push.store.ServerKeyStore;
import com.github.guoyyit.webpushservicejava.push.store.SubscriptionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceRestartTest {

    private static final String AUDIENCE = "http://localhost:8080";

    @TempDir
    Path dataDir;

    private Subscription newSubscription(String id, String endpoint) {
        KeyPair keyPair = VapidService.generateKeyPair();
        Subscription subscription = new Subscription();
        subscription.setId(id);
        subscription.setEndpoint(endpoint);
        subscription.setP256dh(CryptoUtils.encodePublicKey(keyPair.getPublic()));
        subscription.setAuth(CryptoUtils.base64UrlEncode("0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        subscription.setVersion(1);
        subscription.setCreatedAt(Instant.now());
        subscription.setUpdatedAt(Instant.now());
        return subscription;
    }

    private PushMessage message(String payload) {
        PushMessage message = new PushMessage();
        message.setPayload(payload);
        message.setTtlSeconds(3600);
        return message;
    }

    @Test
    void subscriptionsAndKeysSurviveRestart() {
        JsonFileStore fileStore = new JsonFileStore(dataDir);
        SubscriptionStore firstStore = new SubscriptionStore(fileStore);
        firstStore.insert(newSubscription("sub-1", "endpoint://abcdefgh1234"));
        ServerKeyStore firstKeys = new ServerKeyStore(fileStore);
        String publicKeyBefore = CryptoUtils.encodePublicKey(firstKeys.currentKeyPair().getPublic());

        SubscriptionStore restartedStore = new SubscriptionStore(new JsonFileStore(dataDir));
        ServerKeyStore restartedKeys = new ServerKeyStore(new JsonFileStore(dataDir));

        assertTrue(restartedStore.find("sub-1").isPresent());
        assertEquals("endpoint://abcdefgh1234", restartedStore.find("sub-1").orElseThrow().getEndpoint());
        assertEquals(publicKeyBefore, CryptoUtils.encodePublicKey(restartedKeys.currentKeyPair().getPublic()));
        assertEquals(firstKeys.generation(), restartedKeys.generation());
    }

    @Test
    void pendingDeliveriesSurviveRestartAndAreProcessed() {
        EndpointRegistry registry = new EndpointRegistry();
        String endpoint = registry.register();
        MutableClock clock = new MutableClock(Instant.now());
        PushProperties properties = new PushProperties();

        JsonFileStore fileStore = new JsonFileStore(dataDir);
        SubscriptionStore subscriptionStore = new SubscriptionStore(fileStore);
        DeliveryStore deliveryStore = new DeliveryStore(fileStore);
        subscriptionStore.insert(newSubscription("sub-1", endpoint));
        DeliveryService firstService = new DeliveryService(subscriptionStore, deliveryStore, registry,
                new PayloadCipher(clock), properties, clock, new SubscriptionLocks());
        firstService.enqueue("sub-1", message("durable"));
        assertEquals(1, deliveryStore.findBySubscription("sub-1").size());

        SubscriptionStore restartedSubscriptions = new SubscriptionStore(new JsonFileStore(dataDir));
        DeliveryStore restartedDeliveries = new DeliveryStore(new JsonFileStore(dataDir));
        assertEquals(1, restartedDeliveries.findBySubscription("sub-1").size());
        DeliveryService restartedService = new DeliveryService(restartedSubscriptions, restartedDeliveries,
                registry, new PayloadCipher(clock), properties, clock, new SubscriptionLocks());
        restartedService.processDueDeliveries();

        assertEquals(1, registry.received(endpoint).size());
        assertTrue(restartedDeliveries.findBySubscription("sub-1").isEmpty());
    }

    @Test
    void keyRotationInvalidatesTokensSignedWithOldKey() {
        VapidService vapidService = new VapidService();
        ServerKeyStore keyStore = new ServerKeyStore(new JsonFileStore(dataDir));
        String oldToken = vapidService.sign(AUDIENCE, Instant.now().plusSeconds(3600), "mailto:a@b.c",
                keyStore.currentKeyPair().getPrivate());
        assertDoesNotThrow(() -> vapidService.verify(oldToken, AUDIENCE,
                keyStore.currentKeyPair().getPublic(), Instant.now()));

        keyStore.rotate();
        PushException exception = assertThrows(PushException.class, () -> vapidService.verify(oldToken, AUDIENCE,
                keyStore.currentKeyPair().getPublic(), Instant.now()));
        assertEquals(ErrorCode.VAPID_SIGNATURE_INVALID, exception.getCode());

        ServerKeyStore restartedKeys = new ServerKeyStore(new JsonFileStore(dataDir));
        assertEquals(keyStore.generation(), restartedKeys.generation());
        assertEquals(CryptoUtils.encodePublicKey(keyStore.currentKeyPair().getPublic()),
                CryptoUtils.encodePublicKey(restartedKeys.currentKeyPair().getPublic()));
        assertThrows(PushException.class, () -> vapidService.verify(oldToken, AUDIENCE,
                restartedKeys.currentKeyPair().getPublic(), Instant.now()));
    }
}
