package com.github.guoyyit.webpushservicejava.push.service;

import java.util.concurrent.ConcurrentHashMap;

public class SubscriptionLocks {

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public Object lockFor(String subscriptionId) {
        return locks.computeIfAbsent(subscriptionId, id -> new Object());
    }

    public void release(String subscriptionId) {
        locks.remove(subscriptionId);
    }
}
