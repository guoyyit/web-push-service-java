package com.github.guoyyit.webpushservicejava.tests;

import com.github.guoyyit.webpushservicejava.push.crypto.CryptoUtils;
import com.github.guoyyit.webpushservicejava.push.crypto.PayloadCipher;
import com.github.guoyyit.webpushservicejava.push.crypto.VapidService;
import com.github.guoyyit.webpushservicejava.push.config.PushProperties;
import com.github.guoyyit.webpushservicejava.push.endpoint.EndpointRegistry;
import com.github.guoyyit.webpushservicejava.push.exception.PushException;
import com.github.guoyyit.webpushservicejava.push.model.PushMessage;
import com.github.guoyyit.webpushservicejava.push.model.Subscription;
import com.github.guoyyit.webpushservicejava.push.service.DeliveryService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    @TempDir
    Path dataDir;

    private EndpointRegistry registry;
    private SubscriptionStore subscriptionStore;
    private DeliveryStore deliveryStore;
    private SubscriptionLocks locks;
    private SubscriptionService subscriptionService;
    private DeliveryService deliveryService;
    private MutableClock clock;
    private String p256dh;
    private String auth;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.now());
        JsonFileStore fileStore = new JsonFileStore(dataDir);
        subscriptionStore = new SubscriptionStore(fileStore);
        deliveryStore = new DeliveryStore(fileStore);
        registry = new EndpointRegistry();
        locks = new SubscriptionLocks();
        subscriptionService = new SubscriptionService(subscriptionStore, deliveryStore, locks, clock);
        PushProperties properties = new PushProperties();
        deliveryService = new DeliveryService(subscriptionStore, deliveryStore, registry,
                new PayloadCipher(clock), properties, clock, locks);
        KeyPair keyPair = VapidService.generateKeyPair();
        p256dh = CryptoUtils.encodePublicKey(keyPair.getPublic());
        auth = CryptoUtils.base64UrlEncode("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }

    private PushMessage message(String payload, String topic) {
        PushMessage message = new PushMessage();
        message.setPayload(payload);
        message.setTtlSeconds(3600);
        message.setTopic(topic);
        return message;
    }

    @Test
    void concurrentEnqueuesDoNotLoseMessages() throws Exception {
        String endpoint = registry.register();
        Subscription subscription = subscriptionService.create(endpoint, p256dh, auth);
        int threads = 8;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    deliveryService.enqueue(subscription.getId(), message("m-" + threadId + "-" + i, null));
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(threads * perThread, deliveryStore.findBySubscription(subscription.getId()).size());
        deliveryService.processDueDeliveries();
        assertEquals(threads * perThread, registry.received(endpoint).size());
        assertTrue(deliveryStore.findBySubscription(subscription.getId()).isEmpty());
    }

    @Test
    void concurrentTopicEnqueuesCollapseToSinglePending() throws Exception {
        String endpoint = registry.register();
        Subscription subscription = subscriptionService.create(endpoint, p256dh, auth);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            futures.add(pool.submit(() -> {
                start.await();
                deliveryService.enqueue(subscription.getId(), message("v" + threadId, "hot-topic"));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, deliveryStore.findBySubscription(subscription.getId()).size());
        deliveryService.processDueDeliveries();
        assertEquals(1, registry.received(endpoint).size());
    }

    @Test
    void concurrentUpdatesAreNotLost() throws Exception {
        String endpoint = registry.register();
        Subscription subscription = subscriptionService.create(endpoint, p256dh, auth);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                Subscription current = subscriptionService.get(subscription.getId());
                subscriptionService.update(subscription.getId(), current.getEndpoint(), current.getP256dh(),
                        current.getAuth());
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1 + threads, subscriptionService.get(subscription.getId()).getVersion());
    }

    @Test
    void deleteRacingWithDeliveryLeavesConsistentState() throws Exception {
        String endpoint = registry.register();
        Subscription subscription = subscriptionService.create(endpoint, p256dh, auth);
        for (int i = 0; i < 50; i++) {
            deliveryService.enqueue(subscription.getId(), message("m" + i, null));
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger notFoundCount = new AtomicInteger();
        Future<?> deliveryWorker = pool.submit(() -> {
            start.await();
            for (int i = 0; i < 200; i++) {
                deliveryService.processDueDeliveries();
            }
            return null;
        });
        Future<?> deleteWorker = pool.submit(() -> {
            start.await();
            try {
                subscriptionService.delete(subscription.getId());
            } catch (PushException e) {
                notFoundCount.incrementAndGet();
            }
            return null;
        });
        start.countDown();
        deliveryWorker.get(30, TimeUnit.SECONDS);
        deleteWorker.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(subscriptionStore.find(subscription.getId()).isEmpty());
        assertTrue(deliveryStore.findBySubscription(subscription.getId()).isEmpty());
        deliveryService.processDueDeliveries();
        assertTrue(deliveryStore.findBySubscription(subscription.getId()).isEmpty());
    }
}
