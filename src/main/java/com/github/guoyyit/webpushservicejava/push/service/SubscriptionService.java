package com.github.guoyyit.webpushservicejava.push.service;

import com.github.guoyyit.webpushservicejava.push.crypto.CryptoUtils;
import com.github.guoyyit.webpushservicejava.push.endpoint.EndpointRegistry;
import com.github.guoyyit.webpushservicejava.push.exception.ErrorCode;
import com.github.guoyyit.webpushservicejava.push.exception.PushException;
import com.github.guoyyit.webpushservicejava.push.model.Subscription;
import com.github.guoyyit.webpushservicejava.push.store.DeliveryStore;
import com.github.guoyyit.webpushservicejava.push.store.SubscriptionStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class SubscriptionService {

    private static final int MIN_AUTH_SECRET_LENGTH = 16;

    private final SubscriptionStore store;
    private final DeliveryStore deliveryStore;
    private final SubscriptionLocks locks;
    private final Clock clock;

    public SubscriptionService(SubscriptionStore store, DeliveryStore deliveryStore,
                               SubscriptionLocks locks, Clock clock) {
        this.store = store;
        this.deliveryStore = deliveryStore;
        this.locks = locks;
        this.clock = clock;
    }

    public Subscription create(String endpoint, String p256dh, String auth) {
        validate(endpoint, p256dh, auth);
        Instant now = Instant.now(clock);
        Subscription subscription = new Subscription();
        subscription.setId(UUID.randomUUID().toString());
        subscription.setEndpoint(endpoint);
        subscription.setP256dh(p256dh);
        subscription.setAuth(auth);
        subscription.setVersion(1);
        subscription.setCreatedAt(now);
        subscription.setUpdatedAt(now);
        return store.insert(subscription);
    }

    public Subscription update(String id, String endpoint, String p256dh, String auth) {
        validate(endpoint, p256dh, auth);
        synchronized (locks.lockFor(id)) {
            Subscription existing = store.find(id)
                    .orElseThrow(() -> new PushException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                            "subscription not found: " + id));
            existing.setEndpoint(endpoint);
            existing.setP256dh(p256dh);
            existing.setAuth(auth);
            existing.setVersion(existing.getVersion() + 1);
            existing.setUpdatedAt(Instant.now(clock));
            return store.update(existing);
        }
    }

    public Subscription get(String id) {
        return store.find(id)
                .orElseThrow(() -> new PushException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "subscription not found: " + id));
    }

    public List<Subscription> list() {
        return store.findAll();
    }

    public void delete(String id) {
        synchronized (locks.lockFor(id)) {
            if (!store.delete(id)) {
                locks.release(id);
                throw new PushException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "subscription not found: " + id);
            }
            deliveryStore.deleteBySubscription(id);
            locks.release(id);
        }
    }

    private static void validate(String endpoint, String p256dh, String auth) {
        if (!EndpointRegistry.isValidFormat(endpoint)) {
            throw new PushException(ErrorCode.INVALID_ENDPOINT,
                    "endpoint must match " + EndpointRegistry.SCHEME + "<id> format");
        }
        try {
            CryptoUtils.decodeP256PublicKey(p256dh);
        } catch (IllegalArgumentException e) {
            throw new PushException(ErrorCode.INVALID_KEY, "p256dh is not a valid P-256 public key", e);
        }
        final byte[] authBytes;
        try {
            authBytes = CryptoUtils.base64UrlDecode(auth);
        } catch (IllegalArgumentException e) {
            throw new PushException(ErrorCode.INVALID_KEY, "auth secret is not valid base64url", e);
        }
        if (authBytes.length < MIN_AUTH_SECRET_LENGTH) {
            throw new PushException(ErrorCode.INVALID_KEY,
                    "auth secret must be at least " + MIN_AUTH_SECRET_LENGTH + " bytes");
        }
    }
}
