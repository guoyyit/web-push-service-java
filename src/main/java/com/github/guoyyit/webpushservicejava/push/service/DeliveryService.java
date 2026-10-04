package com.github.guoyyit.webpushservicejava.push.service;

import com.github.guoyyit.webpushservicejava.push.config.PushProperties;
import com.github.guoyyit.webpushservicejava.push.crypto.PayloadCipher;
import com.github.guoyyit.webpushservicejava.push.endpoint.EndpointRegistry;
import com.github.guoyyit.webpushservicejava.push.exception.ErrorCode;
import com.github.guoyyit.webpushservicejava.push.exception.PushException;
import com.github.guoyyit.webpushservicejava.push.model.DeliveryState;
import com.github.guoyyit.webpushservicejava.push.model.EndpointResult;
import com.github.guoyyit.webpushservicejava.push.model.PendingDelivery;
import com.github.guoyyit.webpushservicejava.push.model.PushMessage;
import com.github.guoyyit.webpushservicejava.push.model.Subscription;
import com.github.guoyyit.webpushservicejava.push.store.DeliveryStore;
import com.github.guoyyit.webpushservicejava.push.store.SubscriptionStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final SubscriptionStore subscriptionStore;
    private final DeliveryStore deliveryStore;
    private final EndpointRegistry endpointRegistry;
    private final PayloadCipher payloadCipher;
    private final PushProperties properties;
    private final Clock clock;
    private final SubscriptionLocks locks;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "push-delivery-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    public DeliveryService(SubscriptionStore subscriptionStore, DeliveryStore deliveryStore,
                           EndpointRegistry endpointRegistry, PayloadCipher payloadCipher,
                           PushProperties properties, Clock clock, SubscriptionLocks locks) {
        this.subscriptionStore = subscriptionStore;
        this.deliveryStore = deliveryStore;
        this.endpointRegistry = endpointRegistry;
        this.payloadCipher = payloadCipher;
        this.properties = properties;
        this.clock = clock;
        this.locks = locks;
    }

    public PendingDelivery enqueue(String subscriptionId, PushMessage message) {
        if (message == null || message.getPayload() == null) {
            throw new PushException(ErrorCode.INVALID_REQUEST, "message payload is required");
        }
        long ttl = message.getTtlSeconds() > 0 ? message.getTtlSeconds() : properties.getDefaultTtlSeconds();
        if (ttl <= 0) {
            throw new PushException(ErrorCode.MESSAGE_EXPIRED, "message ttl has already elapsed");
        }
        ttl = Math.min(ttl, properties.getMaxTtlSeconds());
        synchronized (locks.lockFor(subscriptionId)) {
            if (subscriptionStore.find(subscriptionId).isEmpty()) {
                throw new PushException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "subscription not found: " + subscriptionId);
            }
            Instant now = Instant.now(clock);
            if (message.getTopic() != null && !message.getTopic().isBlank()) {
                Optional<PendingDelivery> existing =
                        deliveryStore.findPendingByTopic(subscriptionId, message.getTopic());
                if (existing.isPresent()) {
                    PendingDelivery collapsed = existing.get();
                    message.setTtlSeconds(ttl);
                    collapsed.setMessage(message);
                    collapsed.setCreatedAt(now);
                    collapsed.setExpiresAt(now.plusSeconds(ttl));
                    collapsed.setNextAttemptAt(now);
                    collapsed.setLastError(null);
                    return deliveryStore.upsert(collapsed);
                }
            }
            PendingDelivery delivery = new PendingDelivery();
            delivery.setId(UUID.randomUUID().toString());
            delivery.setSubscriptionId(subscriptionId);
            message.setTtlSeconds(ttl);
            delivery.setMessage(message);
            delivery.setState(DeliveryState.PENDING);
            delivery.setAttempts(0);
            delivery.setCreatedAt(now);
            delivery.setExpiresAt(now.plusSeconds(ttl));
            delivery.setNextAttemptAt(now);
            return deliveryStore.upsert(delivery);
        }
    }

    public List<PendingDelivery> pendingFor(String subscriptionId) {
        return deliveryStore.findBySubscription(subscriptionId);
    }

    public void processDueDeliveries() {
        Instant now = Instant.now(clock);
        List<PendingDelivery> due = new ArrayList<>();
        for (PendingDelivery delivery : deliveryStore.findAll()) {
            if (delivery.getState() == DeliveryState.PENDING && !delivery.getNextAttemptAt().isAfter(now)) {
                due.add(delivery);
            }
        }
        due.sort(Comparator.comparing((PendingDelivery d) -> d.getMessage().getUrgency()).reversed()
                .thenComparing(PendingDelivery::getNextAttemptAt));
        for (PendingDelivery delivery : due) {
            try {
                processOne(delivery.getId());
            } catch (Exception e) {
                log.warn("delivery {} processing failed unexpectedly: {}", delivery.getId(), e.toString());
            }
        }
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::processSafely, properties.getSchedulerIntervalMillis(),
                properties.getSchedulerIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public long backoffDelayMillis(int attempts) {
        double delay = properties.getRetryBaseDelayMillis()
                * Math.pow(properties.getRetryBackoffMultiplier(), Math.max(0, attempts - 1));
        return Math.min((long) delay, properties.getMaxRetryDelayMillis());
    }

    private void processSafely() {
        try {
            processDueDeliveries();
        } catch (Exception e) {
            log.warn("delivery scheduler iteration failed: {}", e.toString());
        }
    }

    private void processOne(String deliveryId) {
        Optional<PendingDelivery> current = deliveryStore.find(deliveryId);
        if (current.isEmpty()) {
            return;
        }
        String subscriptionId = current.get().getSubscriptionId();
        synchronized (locks.lockFor(subscriptionId)) {
            Optional<PendingDelivery> fresh = deliveryStore.find(deliveryId);
            if (fresh.isEmpty() || fresh.get().getState() != DeliveryState.PENDING) {
                return;
            }
            PendingDelivery delivery = fresh.get();
            Instant now = Instant.now(clock);
            Optional<Subscription> subscription = subscriptionStore.find(subscriptionId);
            if (subscription.isEmpty()) {
                deliveryStore.delete(deliveryId);
                return;
            }
            if (now.isAfter(delivery.getExpiresAt())) {
                delivery.setState(DeliveryState.EXPIRED);
                delivery.setLastError(ErrorCode.MESSAGE_EXPIRED.name());
                deliveryStore.upsert(delivery);
                return;
            }
            long remainingTtl = Math.max(1, delivery.getExpiresAt().getEpochSecond() - now.getEpochSecond());
            var encrypted = payloadCipher.encrypt(
                    delivery.getMessage().getPayload().getBytes(StandardCharsets.UTF_8),
                    subscription.get().getP256dh(), subscription.get().getAuth(), remainingTtl);
            EndpointResult result = endpointRegistry.deliver(subscription.get().getEndpoint(), encrypted,
                    delivery.getMessage().getUrgency(), delivery.getMessage().getTopic());
            switch (result) {
                case DELIVERED -> deliveryStore.delete(deliveryId);
                case TRANSIENT_FAILURE -> {
                    delivery.setAttempts(delivery.getAttempts() + 1);
                    if (delivery.getAttempts() >= properties.getMaxDeliveryAttempts()) {
                        delivery.setState(DeliveryState.FAILED);
                        delivery.setLastError("MAX_RETRIES_EXCEEDED");
                        deliveryStore.upsert(delivery);
                    } else {
                        delivery.setLastError("TRANSIENT_FAILURE");
                        delivery.setNextAttemptAt(now.plusMillis(backoffDelayMillis(delivery.getAttempts())));
                        deliveryStore.upsert(delivery);
                    }
                }
                case NOT_FOUND, REVOKED -> {
                    subscriptionStore.delete(subscriptionId);
                    deliveryStore.deleteBySubscription(subscriptionId);
                    locks.release(subscriptionId);
                }
            }
        }
    }
}
