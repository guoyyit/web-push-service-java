package com.github.guoyyit.webpushservicejava.domain;

import java.time.Instant;

public class Subscription {
    private String id;
    private String endpoint;
    private String p256dh;
    private String auth;
    private Instant createdAt;
    private Instant updatedAt;

    public Subscription() {
    }

    public Subscription(String id, String endpoint, String p256dh, String auth,
                        Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getP256dh() { return p256dh; }
    public void setP256dh(String p256dh) { this.p256dh = p256dh; }

    public String getAuth() { return auth; }
    public void setAuth(String auth) { this.auth = auth; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
