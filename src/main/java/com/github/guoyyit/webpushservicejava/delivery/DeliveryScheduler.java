package com.github.guoyyit.webpushservicejava.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.webpushservicejava.config.PushProperties;
import com.github.guoyyit.webpushservicejava.crypto.PayloadCryptoService;
import com.github.guoyyit.webpushservicejava.domain.Delivery;
import com.github.guoyyit.webpushservicejava.domain.DeliveryStatus;
import com.github.guoyyit.webpushservicejava.domain.EncryptedPayload;
import com.github.guoyyit.webpushservicejava.domain.Subscription;
import com.github.guoyyit.webpushservicejava.domain.Urgency;
import com.github.guoyyit.webpushservicejava.message.MessageService;
import com.github.guoyyit.webpushservicejava.subscription.SubscriptionService;
import com.github.guoyyit.webpushservicejava.vapid.VapidService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class DeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryScheduler.class);
    private static final String ASSOCIATED_DATA_PREFIX = "push-message:";

    private final MessageService messageService;
    private final SubscriptionService subscriptionService;
    private final PayloadCryptoService payloadCryptoService;
    private final PushDeliveryClient deliveryClient;
    private final VapidService vapidService;
    private final PushProperties properties;
    private final ObjectMapper objectMapper;

    private ScheduledExecutorService executor;

    public DeliveryScheduler(MessageService messageService, SubscriptionService subscriptionService,
                             PayloadCryptoService payloadCryptoService, PushDeliveryClient deliveryClient,
                             VapidService vapidService, PushProperties properties, ObjectMapper objectMapper) {
        this.messageService = messageService;
        this.subscriptionService = subscriptionService;
        this.payloadCryptoService = payloadCryptoService;
        this.deliveryClient = deliveryClient;
        this.vapidService = vapidService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "delivery-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::safeTick,
                properties.getDispatchIntervalMillis(),
                properties.getDispatchIntervalMillis(),
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public void tick() {
        safeTick();
    }

    private void safeTick() {
        try {
            processDue();
        } catch (Throwable throwable) {
            log.error("Delivery tick failed", throwable);
        }
    }

    public void processDue() {
        Instant now = Instant.now();
        for (Delivery delivery : messageService.dueDeliveries(now)) {
            processOne(delivery, now);
        }
    }

    private void processOne(Delivery delivery, Instant now) {
        subscriptionService.runWithLock(delivery.getSubscriptionId(), () -> {
            Subscription subscription = subscriptionService.get(delivery.getSubscriptionId());
            Delivery current = messageService.get(delivery.getId());
            if (subscription == null || current == null) {
                return;
            }
            if (current.getStatus() != DeliveryStatus.PENDING
                    && current.getStatus() != DeliveryStatus.RETRY_WAIT) {
                return;
            }
            if (current.getExpiresAt() != null && !current.getExpiresAt().isAfter(now)) {
                current.setStatus(DeliveryStatus.EXPIRED);
                current.setLastError("message TTL expired before delivery");
                messageService.update(current);
                return;
            }
            attemptDelivery(current, subscription);
        });
    }

    private void attemptDelivery(Delivery delivery, Subscription subscription) {
        delivery.setAttempts(delivery.getAttempts() + 1);
        String associatedData = ASSOCIATED_DATA_PREFIX + delivery.getId();
        long payloadExpiry = delivery.getExpiresAt().toEpochMilli();

        EncryptedPayload encryptedPayload = payloadCryptoService.encrypt(
                subscription, delivery.getPayload(), associatedData, payloadExpiry);
        String body;
        try {
            body = objectMapper.writeValueAsString(encryptedPayload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize encrypted payload", e);
        }

        String audience = audienceOf(subscription.getEndpoint());
        String authorization = vapidService.signAuthorization(audience,
                "mailto:webpush@localhost");

        PushDeliveryClient.DeliveryResult result = deliveryClient.deliver(
                subscription.getEndpoint(), body, authorization,
                delivery.getTtlSeconds(), urgencyHeader(delivery.getUrgency()), delivery.getTopic());

        switch (result.getType()) {
            case SUCCESS -> {
                delivery.setStatus(DeliveryStatus.SENT);
                delivery.setLastError(null);
                messageService.update(delivery);
            }
            case PERMANENT_FAILURE -> {
                delivery.setStatus(DeliveryStatus.FAILED_PERMANENT);
                delivery.setLastError(result.getReason());
                messageService.update(delivery);
                subscriptionService.delete(subscription.getId());
                messageService.failOutstandingForSubscription(subscription.getId(),
                        "subscription cleaned up: " + result.getReason());
            }
            case TEMPORARY_FAILURE -> scheduleRetry(delivery, result.getReason());
        }
    }

    private void scheduleRetry(Delivery delivery, String reason) {
        int attempt = delivery.getAttempts();
        if (attempt >= properties.getMaxRetries()) {
            delivery.setStatus(DeliveryStatus.FAILED_RETRIES_EXHAUSTED);
            delivery.setLastError("retry budget exhausted after " + attempt + " attempts: " + reason);
        } else {
            long backoff = Math.round(properties.getRetryBackoffMillis()
                    * Math.pow(properties.getBackoffMultiplier(), attempt - 1));
            delivery.setStatus(DeliveryStatus.RETRY_WAIT);
            delivery.setNotBefore(Instant.now().plusMillis(backoff));
            delivery.setLastError(reason + "; next attempt at " + delivery.getNotBefore());
        }
        messageService.update(delivery);
    }

    private String urgencyHeader(Urgency urgency) {
        if (urgency == null) {
            return "normal";
        }
        return switch (urgency) {
            case VERY_LOW -> "very-low";
            case LOW -> "low";
            case NORMAL -> "normal";
            case HIGH -> "high";
        };
    }

    private String audienceOf(String endpoint) {
        URI uri = URI.create(endpoint);
        int port = uri.getPort();
        String portPart = port == -1 || (port == 80 && "http".equalsIgnoreCase(uri.getScheme()))
                || (port == 443 && "https".equalsIgnoreCase(uri.getScheme()))
                ? "" : ":" + port;
        return uri.getScheme() + "://" + uri.getHost() + portPart;
    }
}
