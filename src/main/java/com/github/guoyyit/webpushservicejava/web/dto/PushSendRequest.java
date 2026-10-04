package com.github.guoyyit.webpushservicejava.web.dto;

public class PushSendRequest {
    private String payload;
    private Integer ttlSeconds;
    private String urgency;
    private String topic;

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Integer getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Integer ttlSeconds) { this.ttlSeconds = ttlSeconds; }

    public String getUrgency() { return urgency; }
    public void setUrgency(String urgency) { this.urgency = urgency; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
}
