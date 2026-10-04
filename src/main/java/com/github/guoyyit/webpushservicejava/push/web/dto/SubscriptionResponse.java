package com.github.guoyyit.webpushservicejava.push.web.dto;

import com.github.guoyyit.webpushservicejava.push.model.Subscription;

public class SubscriptionResponse {
    private String id;
    private String endpoint;
    private String p256dh;
    private String auth;
    private long version;

    public static SubscriptionResponse from(Subscription subscription) {
        SubscriptionResponse response = new SubscriptionResponse();
        response.id = subscription.getId();
        response.endpoint = subscription.getEndpoint();
        response.p256dh = subscription.getP256dh();
        response.auth = subscription.getAuth();
        response.version = subscription.getVersion();
        return response;
    }

    public String getId() {
        return id;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuth() {
        return auth;
    }

    public long getVersion() {
        return version;
    }
}
