package com.github.guoyyit.webpushservicejava.domain;

import java.time.Instant;

public class PendingMessage {
    private String subscriptionId;
    private String topic;
    private String payload;
    private Integer ttlSeconds;
    private Urgency urgency;
    private Instant enqueuedAt;

    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Integer getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Integer ttlSeconds) { this.ttlSeconds = ttlSeconds; }

    public Urgency getUrgency() { return urgency; }
    public void setUrgency(Urgency urgency) { this.urgency = urgency; }

    public Instant getEnqueuedAt() { return enqueuedAt; }
    public void setEnqueuedAt(Instant enqueuedAt) { this.enqueuedAt = enqueuedAt; }
}
