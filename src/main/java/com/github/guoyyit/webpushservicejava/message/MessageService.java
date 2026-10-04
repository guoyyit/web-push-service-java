package com.github.guoyyit.webpushservicejava.message;

import com.github.guoyyit.webpushservicejava.domain.Delivery;
import com.github.guoyyit.webpushservicejava.domain.DeliveryStatus;
import com.github.guoyyit.webpushservicejava.domain.ErrorCode;
import com.github.guoyyit.webpushservicejava.domain.Urgency;
import com.github.guoyyit.webpushservicejava.persistence.PersistenceService;
import com.github.guoyyit.webpushservicejava.subscription.SubscriptionService;
import com.github.guoyyit.webpushservicejava.web.ServiceException;
import com.github.guoyyit.webpushservicejava.web.dto.PushSendRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class MessageService {

    private final PersistenceService persistenceService;
    private final SubscriptionService subscriptionService;
    private final Map<String, Delivery> deliveries = new ConcurrentHashMap<>();
    private final ReentrantLock stateLock = new ReentrantLock();

    public MessageService(PersistenceService persistenceService, SubscriptionService subscriptionService) {
        this.persistenceService = persistenceService;
        this.subscriptionService = subscriptionService;
        for (Delivery delivery : persistenceService.loadDeliveries()) {
            deliveries.put(delivery.getId(), delivery);
        }
    }

    public EnqueueResult enqueue(String subscriptionId, PushSendRequest request) {
        subscriptionService.require(subscriptionId);
        validate(request);

        Instant now = Instant.now();
        int ttl = request.getTtlSeconds() == null ? 2419200 : request.getTtlSeconds();
        Urgency urgency = request.getUrgency() == null || request.getUrgency().isBlank()
                ? Urgency.NORMAL : Urgency.valueOf(request.getUrgency().trim().toUpperCase());
        Delivery delivery = new Delivery();
        delivery.setId(UUID.randomUUID().toString());
        delivery.setSubscriptionId(subscriptionId);
        delivery.setPayload(request.getPayload() == null ? "" : request.getPayload());
        delivery.setTtlSeconds(ttl);
        delivery.setUrgency(urgency);
        delivery.setTopic(request.getTopic() == null || request.getTopic().isBlank()
                ? null : request.getTopic().trim());
        delivery.setEnqueuedAt(now);
        delivery.setExpiresAt(now.plusSeconds(ttl));
        delivery.setNotBefore(now);
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setAttempts(0);

        Delivery coalesced = null;
        stateLock.lock();
        try {
            if (delivery.getTopic() != null) {
                coalesced = findCoalescedCandidate(subscriptionId, delivery.getTopic());
                if (coalesced != null) {
                    coalesced.setStatus(DeliveryStatus.COALESCED);
                    coalesced.setLastError("coalesced by newer message with the same topic");
                }
            }
            deliveries.put(delivery.getId(), delivery);
            persist();
        } finally {
            stateLock.unlock();
        }
        return new EnqueueResult(delivery, coalesced == null ? null : coalesced.getId());
    }

    public record EnqueueResult(Delivery delivery, String coalescedDeliveryId) {
    }

    private Delivery findCoalescedCandidate(String subscriptionId, String topic) {
        Delivery candidate = null;
        for (Delivery existing : deliveries.values()) {
            if (!existing.getSubscriptionId().equals(subscriptionId)) {
                continue;
            }
            if (!topic.equals(existing.getTopic())) {
                continue;
            }
            if (existing.getStatus() != DeliveryStatus.PENDING
                    && existing.getStatus() != DeliveryStatus.RETRY_WAIT) {
                continue;
            }
            if (candidate == null || existing.getEnqueuedAt().isAfter(candidate.getEnqueuedAt())) {
                candidate = existing;
            }
        }
        return candidate;
    }

    private void validate(PushSendRequest request) {
        if (request == null) {
            throw new ServiceException(ErrorCode.INVALID_ARGUMENT, "Request body is required");
        }
        if (request.getTtlSeconds() != null && request.getTtlSeconds() < 0) {
            throw new ServiceException(ErrorCode.INVALID_ARGUMENT, "ttlSeconds must not be negative");
        }
        if (request.getUrgency() != null && !request.getUrgency().isBlank()) {
            try {
                Urgency.valueOf(request.getUrgency().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ServiceException(ErrorCode.INVALID_ARGUMENT,
                        "urgency must be one of VERY_LOW, LOW, NORMAL, HIGH");
            }
        }
        if (request.getTopic() != null && request.getTopic().length() > 32) {
            throw new ServiceException(ErrorCode.INVALID_ARGUMENT, "topic must be at most 32 characters");
        }
    }

    public Delivery get(String id) {
        return deliveries.get(id);
    }

    public Delivery require(String id) {
        Delivery delivery = deliveries.get(id);
        if (delivery == null) {
            throw new ServiceException(ErrorCode.INVALID_ARGUMENT, "Delivery '" + id + "' does not exist");
        }
        return delivery;
    }

    public List<Delivery> listForSubscription(String subscriptionId) {
        return deliveries.values().stream()
                .filter(delivery -> delivery.getSubscriptionId().equals(subscriptionId))
                .toList();
    }

    public List<Delivery> listAll() {
        return List.copyOf(deliveries.values());
    }

    public List<Delivery> dueDeliveries(Instant now) {
        List<Delivery> due = new ArrayList<>();
        stateLock.lock();
        try {
            for (Delivery delivery : deliveries.values()) {
                if (delivery.getStatus() == DeliveryStatus.PENDING
                        || delivery.getStatus() == DeliveryStatus.RETRY_WAIT) {
                    if (!delivery.getNotBefore().isAfter(now)) {
                        due.add(delivery);
                    }
                }
            }
        } finally {
            stateLock.unlock();
        }
        return due;
    }

    public void update(Delivery delivery) {
        stateLock.lock();
        try {
            deliveries.put(delivery.getId(), delivery);
            persist();
        } finally {
            stateLock.unlock();
        }
    }

    public void updateBatch(List<Delivery> changed) {
        if (changed.isEmpty()) {
            return;
        }
        stateLock.lock();
        try {
            for (Delivery delivery : changed) {
                deliveries.put(delivery.getId(), delivery);
            }
            persist();
        } finally {
            stateLock.unlock();
        }
    }

    public void removeForSubscription(String subscriptionId) {
        stateLock.lock();
        try {
            boolean changed = deliveries.values()
                    .removeIf(delivery -> delivery.getSubscriptionId().equals(subscriptionId));
            if (changed) {
                persist();
            }
        } finally {
            stateLock.unlock();
        }
    }

    public void failOutstandingForSubscription(String subscriptionId, String reason) {
        stateLock.lock();
        try {
            boolean changed = false;
            for (Delivery delivery : deliveries.values()) {
                if (!delivery.getSubscriptionId().equals(subscriptionId)) {
                    continue;
                }
                if (delivery.getStatus() == DeliveryStatus.PENDING
                        || delivery.getStatus() == DeliveryStatus.RETRY_WAIT) {
                    delivery.setStatus(DeliveryStatus.FAILED_PERMANENT);
                    delivery.setLastError(reason);
                    changed = true;
                }
            }
            if (changed) {
                persist();
            }
        } finally {
            stateLock.unlock();
        }
    }

    public ReentrantLock stateWriteLock() {
        return stateLock;
    }

    private void persist() {
        persistenceService.saveDeliveries(List.copyOf(deliveries.values()));
    }
}
