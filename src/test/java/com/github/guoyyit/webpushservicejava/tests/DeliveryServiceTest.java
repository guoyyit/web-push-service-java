package com.github.guoyyit.webpushservicejava.tests;

import com.github.guoyyit.webpushservicejava.push.config.PushProperties;
import com.github.guoyyit.webpushservicejava.push.crypto.CryptoUtils;
import com.github.guoyyit.webpushservicejava.push.crypto.PayloadCipher;
import com.github.guoyyit.webpushservicejava.push.crypto.VapidService;
import com.github.guoyyit.webpushservicejava.push.endpoint.EndpointRegistry;
import com.github.guoyyit.webpushservicejava.push.model.DeliveryState;
import com.github.guoyyit.webpushservicejava.push.model.PendingDelivery;
import com.github.guoyyit.webpushservicejava.push.model.PushMessage;
import com.github.guoyyit.webpushservicejava.push.model.Subscription;
import com.github.guoyyit.webpushservicejava.push.service.DeliveryService;
import com.github.guoyyit.webpushservicejava.push.service.SubscriptionLocks;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryServiceTest {

    @TempDir
    Path dataDir;

    private MutableClock clock;
    private EndpointRegistry registry;
    private SubscriptionStore subscriptionStore;
    private DeliveryStore deliveryStore;
    private DeliveryService deliveryService;
    private PayloadCipher payloadCipher;
    private KeyPair subscriberKeys;
    private String authSecret;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.now());
        JsonFileStore fileStore = new JsonFileStore(dataDir);
        subscriptionStore = new SubscriptionStore(fileStore);
        deliveryStore = new DeliveryStore(fileStore);
        registry = new EndpointRegistry();
        payloadCipher = new PayloadCipher(clock);
        PushProperties properties = new PushProperties();
        properties.setMaxDeliveryAttempts(3);
        properties.setRetryBaseDelayMillis(100);
        properties.setRetryBackoffMultiplier(2.0);
        properties.setMaxRetryDelayMillis(500);
        deliveryService = new DeliveryService(subscriptionStore, deliveryStore, registry, payloadCipher,
                properties, clock, new SubscriptionLocks());
        subscriberKeys = VapidService.generateKeyPair();
        authSecret = CryptoUtils.base64UrlEncode("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }

    private Subscription newSubscription(String endpoint) {
        Subscription subscription = new Subscription();
        subscription.setId("sub-" + System.nanoTime());
        subscription.setEndpoint(endpoint);
        subscription.setP256dh(CryptoUtils.encodePublicKey(subscriberKeys.getPublic()));
        subscription.setAuth(authSecret);
        subscription.setVersion(1);
        subscription.setCreatedAt(Instant.now(clock));
        subscription.setUpdatedAt(Instant.now(clock));
        return subscriptionStore.insert(subscription);
    }

    private PushMessage message(String payload, long ttlSeconds, String topic) {
        PushMessage message = new PushMessage();
        message.setPayload(payload);
        message.setTtlSeconds(ttlSeconds);
        message.setTopic(topic);
        return message;
    }

    @Test
    void deliveredMessageReachesEndpointAndIsDecryptable() throws Exception {
        String endpoint = registry.register();
        Subscription subscription = newSubscription(endpoint);
        deliveryService.enqueue(subscription.getId(), message("hello", 3600, null));
        deliveryService.processDueDeliveries();

        List<EndpointRegistry.ReceivedMessage> received = registry.received(endpoint);
        assertEquals(1, received.size());
        byte[] plaintext = payloadCipher.decrypt(received.get(0).payload(),
                CryptoUtils.encodePrivateKey(subscriberKeys.getPrivate()),
                CryptoUtils.encodePublicKey(subscriberKeys.getPublic()), authSecret);
        assertEquals("hello", new String(plaintext, StandardCharsets.UTF_8));
        assertTrue(deliveryStore.findBySubscription(subscription.getId()).isEmpty());
    }

    @Test
    void expiredMessageIsNotDelivered() {
        String endpoint = registry.register();
        Subscription subscription = newSubscription(endpoint);
        deliveryService.enqueue(subscription.getId(), message("stale", 30, null));
        clock.advanceSeconds(60);
        deliveryService.processDueDeliveries();

        assertTrue(registry.received(endpoint).isEmpty());
        List<PendingDelivery> deliveries = deliveryStore.findBySubscription(subscription.getId());
        assertEquals(1, deliveries.size());
        assertEquals(DeliveryState.EXPIRED, deliveries.get(0).getState());
        assertEquals("MESSAGE_EXPIRED", deliveries.get(0).getLastError());
    }

    @Test
    void topicCollapseKeepsOnlyLatestMessage() throws Exception {
        String endpoint = registry.register();
        Subscription subscription = newSubscription(endpoint);
        deliveryService.enqueue(subscription.getId(), message("first", 3600, "topic-1"));
        deliveryService.enqueue(subscription.getId(), message("second", 3600, "topic-1"));
        deliveryService.enqueue(subscription.getId(), message("other", 3600, "topic-2"));

        assertEquals(2, deliveryStore.findBySubscription(subscription.getId()).size());
        deliveryService.processDueDeliveries();

        List<EndpointRegistry.ReceivedMessage> received = registry.received(endpoint);
        assertEquals(2, received.size());
        String privateKey = CryptoUtils.encodePrivateKey(subscriberKeys.getPrivate());
        String publicKey = CryptoUtils.encodePublicKey(subscriberKeys.getPublic());
        List<String> plaintexts = new java.util.ArrayList<>();
        for (EndpointRegistry.ReceivedMessage message : received) {
            plaintexts.add(new String(payloadCipher.decrypt(message.payload(), privateKey, publicKey, authSecret),
                    StandardCharsets.UTF_8));
        }
        assertTrue(plaintexts.contains("second"));
        assertTrue(plaintexts.contains("other"));
        assertTrue(!plaintexts.contains("first"));
    }

    @Test
    void transientFailureRetriesUpToConfiguredCap() {
        String endpoint = registry.register();
        Subscription subscription = newSubscription(endpoint);
        registry.failTransiently(endpoint, 10);
        PendingDelivery delivery = deliveryService.enqueue(subscription.getId(), message("retry", 3600, null));

        deliveryService.processDueDeliveries();
        PendingDelivery afterFirst = deliveryStore.find(delivery.getId()).orElseThrow();
        assertEquals(1, afterFirst.getAttempts());
        assertEquals(DeliveryState.PENDING, afterFirst.getState());
        assertTrue(afterFirst.getNextAttemptAt().isAfter(Instant.now(clock)));

        clock.advanceSeconds(1);
        deliveryService.processDueDeliveries();
        assertEquals(2, deliveryStore.find(delivery.getId()).orElseThrow().getAttempts());

        clock.advanceSeconds(1);
        deliveryService.processDueDeliveries();
        PendingDelivery afterThird = deliveryStore.find(delivery.getId()).orElseThrow();
        assertEquals(3, afterThird.getAttempts());
        assertEquals(DeliveryState.FAILED, afterThird.getState());
        assertEquals("MAX_RETRIES_EXCEEDED", afterThird.getLastError());

        clock.advanceSeconds(10);
        deliveryService.processDueDeliveries();
        assertEquals(3, deliveryStore.find(delivery.getId()).orElseThrow().getAttempts());
        assertTrue(registry.received(endpoint).isEmpty());
    }

    @Test
    void unknownEndpointRemovesSubscriptionAndDeliveries() {
        Subscription subscription = newSubscription("endpoint://missingendpoint1");
        deliveryService.enqueue(subscription.getId(), message("gone", 3600, null));
        deliveryService.processDueDeliveries();

        assertTrue(subscriptionStore.find(subscription.getId()).isEmpty());
        assertTrue(deliveryStore.findBySubscription(subscription.getId()).isEmpty());
    }

    @Test
    void revokedEndpointRemovesSubscriptionAndDeliveries() {
        String endpoint = registry.register();
        Subscription subscription = newSubscription(endpoint);
        deliveryService.enqueue(subscription.getId(), message("revoked", 3600, null));
        registry.revoke(endpoint);
        deliveryService.processDueDeliveries();

        assertTrue(subscriptionStore.find(subscription.getId()).isEmpty());
        assertTrue(deliveryStore.findBySubscription(subscription.getId()).isEmpty());
        assertTrue(registry.received(endpoint).isEmpty());
    }

    @Test
    void backoffDelayIsExponentialAndCapped() {
        assertEquals(100, deliveryService.backoffDelayMillis(1));
        assertEquals(200, deliveryService.backoffDelayMillis(2));
        assertEquals(400, deliveryService.backoffDelayMillis(3));
        assertEquals(500, deliveryService.backoffDelayMillis(4));
        assertEquals(500, deliveryService.backoffDelayMillis(20));
    }
}
