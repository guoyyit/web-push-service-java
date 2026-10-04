package com.github.guoyyit.webpushservicejava.push.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.webpushservicejava.push.model.Subscription;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SubscriptionStore {

    private static final String FILE = "subscriptions";

    private final JsonFileStore fileStore;
    private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();

    public SubscriptionStore(JsonFileStore fileStore) {
        this.fileStore = fileStore;
        ObjectNode root = fileStore.read(FILE);
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            Subscription subscription = JsonFileStore.mapper().convertValue(field.getValue(), Subscription.class);
            subscriptions.put(subscription.getId(), subscription);
        }
    }

    public synchronized Subscription insert(Subscription subscription) {
        if (subscriptions.containsKey(subscription.getId())) {
            throw new IllegalStateException("subscription already exists: " + subscription.getId());
        }
        subscriptions.put(subscription.getId(), subscription);
        persist();
        return copy(subscription);
    }

    public synchronized Subscription update(Subscription subscription) {
        if (!subscriptions.containsKey(subscription.getId())) {
            throw new IllegalStateException("subscription does not exist: " + subscription.getId());
        }
        subscriptions.put(subscription.getId(), subscription);
        persist();
        return copy(subscription);
    }

    public synchronized Optional<Subscription> find(String id) {
        return Optional.ofNullable(subscriptions.get(id)).map(this::copy);
    }

    public synchronized List<Subscription> findAll() {
        List<Subscription> result = new ArrayList<>();
        for (Subscription subscription : subscriptions.values()) {
            result.add(copy(subscription));
        }
        return result;
    }

    public synchronized boolean delete(String id) {
        if (subscriptions.remove(id) == null) {
            return false;
        }
        persist();
        return true;
    }

    private void persist() {
        ObjectNode root = JsonFileStore.mapper().createObjectNode();
        for (Subscription subscription : subscriptions.values()) {
            root.set(subscription.getId(), JsonFileStore.mapper().valueToTree(subscription));
        }
        fileStore.write(FILE, root);
    }

    private Subscription copy(Subscription subscription) {
        return JsonFileStore.mapper().convertValue(JsonFileStore.mapper().valueToTree(subscription), Subscription.class);
    }
}
