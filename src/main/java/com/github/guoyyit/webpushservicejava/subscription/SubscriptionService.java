package com.github.guoyyit.webpushservicejava.subscription;

import com.github.guoyyit.webpushservicejava.crypto.EcKeyUtils;
import com.github.guoyyit.webpushservicejava.domain.ErrorCode;
import com.github.guoyyit.webpushservicejava.domain.Subscription;
import com.github.guoyyit.webpushservicejava.message.MessageService;
import com.github.guoyyit.webpushservicejava.persistence.PersistenceService;
import com.github.guoyyit.webpushservicejava.web.ServiceException;
import com.github.guoyyit.webpushservicejava.web.dto.SubscriptionRequest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class SubscriptionService {

    private final PersistenceService persistenceService;
    private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ReentrantLock registryLock = new ReentrantLock();
    private final ObjectProvider<MessageService> messageServiceProvider;

    public SubscriptionService(PersistenceService persistenceService,
                               ObjectProvider<MessageService> messageServiceProvider) {
        this.persistenceService = persistenceService;
        this.messageServiceProvider = messageServiceProvider;
        for (Subscription subscription : persistenceService.loadSubscriptions()) {
            subscriptions.put(subscription.getId(), subscription);
        }
    }

    public Subscription create(SubscriptionRequest request) {
        validate(request);
        Instant now = Instant.now();
        Subscription subscription = new Subscription(
                UUID.randomUUID().toString(),
                request.getEndpoint().trim(),
                normalizeBase64(request.getP256dh()),
                normalizeBase64(request.getAuth()),
                now, now);
        registryLock.lock();
        try {
            subscriptions.put(subscription.getId(), subscription);
            persist();
        } finally {
            registryLock.unlock();
        }
        return subscription;
    }

    public Subscription update(String id, SubscriptionRequest request) {
        validate(request);
        registryLock.lock();
        try {
            Subscription existing = subscriptions.get(id);
            if (existing == null) {
                throw new ServiceException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "Subscription '" + id + "' does not exist");
            }
            existing.setEndpoint(request.getEndpoint().trim());
            existing.setP256dh(normalizeBase64(request.getP256dh()));
            existing.setAuth(normalizeBase64(request.getAuth()));
            existing.setUpdatedAt(Instant.now());
            persist();
            return existing;
        } finally {
            registryLock.unlock();
        }
    }

    public Subscription get(String id) {
        registryLock.lock();
        try {
            return subscriptions.get(id);
        } finally {
            registryLock.unlock();
        }
    }

    public Subscription require(String id) {
        Subscription subscription = get(id);
        if (subscription == null) {
            throw new ServiceException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                    "Subscription '" + id + "' does not exist");
        }
        return subscription;
    }

    public Collection<Subscription> list() {
        registryLock.lock();
        try {
            return List.copyOf(subscriptions.values());
        } finally {
            registryLock.unlock();
        }
    }

    public boolean delete(String id) {
        registryLock.lock();
        try {
            Subscription removed = subscriptions.remove(id);
            if (removed != null) {
                persist();
                MessageService messageService = messageServiceProvider.getIfAvailable();
                if (messageService != null) {
                    messageService.failOutstandingForSubscription(id,
                            "subscription deleted; pending delivery cancelled");
                }
                return true;
            }
            return false;
        } finally {
            registryLock.unlock();
        }
    }

    public ReentrantLock lockFor(String subscriptionId) {
        return locks.computeIfAbsent(subscriptionId, ignored -> new ReentrantLock());
    }

    public void runWithLock(String subscriptionId, Runnable action) {
        ReentrantLock lock = lockFor(subscriptionId);
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    private void validate(SubscriptionRequest request) {
        if (request == null) {
            throw new ServiceException(ErrorCode.INVALID_ARGUMENT, "Request body is required");
        }
        validateEndpoint(request.getEndpoint());
        validateP256dh(request.getP256dh());
        validateAuth(request.getAuth());
    }

    private void validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new ServiceException(ErrorCode.INVALID_ENDPOINT, "Endpoint must not be empty");
        }
        String trimmed = endpoint.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(ErrorCode.INVALID_ENDPOINT, "Endpoint is not a valid URI");
        }
        if (uri.getScheme() == null
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new ServiceException(ErrorCode.INVALID_ENDPOINT, "Endpoint must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ServiceException(ErrorCode.INVALID_ENDPOINT, "Endpoint must include a host");
        }
        UriComponents components = UriComponentsBuilder.fromUriString(trimmed).build();
        if (components.getPath() == null || !components.getPath().contains("/push/")) {
            throw new ServiceException(ErrorCode.INVALID_ENDPOINT,
                    "Endpoint path must reference a registered push endpoint (/push/{id})");
        }
    }

    private void validateP256dh(String p256dh) {
        byte[] decoded = decodeBase64Key(p256dh, ErrorCode.INVALID_PUBLIC_KEY,
                "p256dh must be base64 encoded");
        try {
            EcKeyUtils.decodeUncompressedPublic(decoded);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(ErrorCode.INVALID_PUBLIC_KEY,
                    "p256dh must be a 65-byte uncompressed P-256 public key", e);
        }
    }

    private void validateAuth(String auth) {
        byte[] decoded = decodeBase64Key(auth, ErrorCode.INVALID_ARGUMENT,
                "auth must be base64 encoded");
        if (decoded.length != 16) {
            throw new ServiceException(ErrorCode.INVALID_ARGUMENT, "auth secret must be exactly 16 bytes");
        }
    }

    private byte[] decodeBase64Key(String value, ErrorCode errorCode, String message) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(errorCode, message);
        }
        try {
            String normalized = normalizeBase64(value);
            return Base64.getUrlDecoder().decode(normalized);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(errorCode, message, e);
        }
    }

    private String normalizeBase64(String value) {
        String normalized = value.trim();
        int padding = (4 - normalized.length() % 4) % 4;
        return normalized + "=".repeat(padding);
    }

    public String stableFingerprint(Subscription subscription) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(subscription.getId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(subscription.getEndpoint().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void persist() {
        persistenceService.saveSubscriptions(List.copyOf(subscriptions.values()));
    }
}
