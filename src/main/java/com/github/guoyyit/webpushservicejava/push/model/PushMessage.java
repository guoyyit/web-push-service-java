package com.github.guoyyit.webpushservicejava.push.model;

public class PushMessage {
    private String payload;
    private long ttlSeconds;
    private Urgency urgency = Urgency.NORMAL;
    private String topic;

    public PushMessage() {
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public Urgency getUrgency() {
        return urgency;
    }

    public void setUrgency(Urgency urgency) {
        this.urgency = urgency;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }
}
