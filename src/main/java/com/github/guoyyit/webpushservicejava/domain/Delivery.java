package com.github.guoyyit.webpushservicejava.domain;

import java.time.Instant;

public class Delivery {
    private String id;
    private String subscriptionId;
    private String payload;
    private Integer ttlSeconds;
    private Urgency urgency;
    private String topic;
    private Instant enqueuedAt;
    private Instant expiresAt;
    private Instant notBefore;
    private DeliveryStatus status;
    private int attempts;
    private String lastError;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Integer getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Integer ttlSeconds) { this.ttlSeconds = ttlSeconds; }

    public Urgency getUrgency() { return urgency; }
    public void setUrgency(Urgency urgency) { this.urgency = urgency; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public Instant getEnqueuedAt() { return enqueuedAt; }
    public void setEnqueuedAt(Instant enqueuedAt) { this.enqueuedAt = enqueuedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getNotBefore() { return notBefore; }
    public void setNotBefore(Instant notBefore) { this.notBefore = notBefore; }

    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
