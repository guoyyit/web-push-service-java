package com.github.guoyyit.webpushservicejava.push.service;

import com.github.guoyyit.webpushservicejava.push.config.PushProperties;
import com.github.guoyyit.webpushservicejava.push.crypto.CryptoUtils;
import com.github.guoyyit.webpushservicejava.push.crypto.VapidService;
import com.github.guoyyit.webpushservicejava.push.exception.ErrorCode;
import com.github.guoyyit.webpushservicejava.push.exception.PushException;
import com.github.guoyyit.webpushservicejava.push.model.PendingDelivery;
import com.github.guoyyit.webpushservicejava.push.model.PushMessage;
import com.github.guoyyit.webpushservicejava.push.store.ServerKeyStore;

import java.time.Clock;
import java.time.Instant;

public class PushService {

    private final SubscriptionService subscriptionService;
    private final DeliveryService deliveryService;
    private final VapidService vapidService;
    private final ServerKeyStore serverKeyStore;
    private final PushProperties properties;
    private final Clock clock;

    public PushService(SubscriptionService subscriptionService, DeliveryService deliveryService,
                       VapidService vapidService, ServerKeyStore serverKeyStore,
                       PushProperties properties, Clock clock) {
        this.subscriptionService = subscriptionService;
        this.deliveryService = deliveryService;
        this.vapidService = vapidService;
        this.serverKeyStore = serverKeyStore;
        this.properties = properties;
        this.clock = clock;
    }

    public PendingDelivery send(String authorizationHeader, String subscriptionId, PushMessage message) {
        String token = extractToken(authorizationHeader);
        vapidService.verify(token, properties.getVapidAudience(),
                serverKeyStore.currentKeyPair().getPublic(), Instant.now(clock));
        subscriptionService.get(subscriptionId);
        return deliveryService.enqueue(subscriptionId, message);
    }

    public String issueVapidToken() {
        Instant expiresAt = Instant.now(clock).plusSeconds(properties.getVapidExpirySeconds());
        return vapidService.sign(properties.getVapidAudience(), expiresAt, properties.getVapidSubject(),
                serverKeyStore.currentKeyPair().getPrivate());
    }

    public String publicKey() {
        return CryptoUtils.encodePublicKey(serverKeyStore.currentKeyPair().getPublic());
    }

    public long rotateKeys() {
        serverKeyStore.rotate();
        return serverKeyStore.generation();
    }

    private static String extractToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new PushException(ErrorCode.VAPID_MISSING, "Authorization header with VAPID token is required");
        }
        String value = authorizationHeader.trim();
        for (String scheme : new String[]{"Bearer ", "WebPush "}) {
            if (value.regionMatches(true, 0, scheme, 0, scheme.length())) {
                return value.substring(scheme.length()).trim();
            }
        }
        return value;
    }
}
