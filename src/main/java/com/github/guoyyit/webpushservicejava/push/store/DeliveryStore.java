package com.github.guoyyit.webpushservicejava.push.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.webpushservicejava.push.model.DeliveryState;
import com.github.guoyyit.webpushservicejava.push.model.PendingDelivery;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DeliveryStore {

    private static final String FILE = "deliveries";

    private final JsonFileStore fileStore;
    private final Map<String, PendingDelivery> deliveries = new LinkedHashMap<>();

    public DeliveryStore(JsonFileStore fileStore) {
        this.fileStore = fileStore;
        ObjectNode root = fileStore.read(FILE);
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            PendingDelivery delivery = JsonFileStore.mapper().convertValue(field.getValue(), PendingDelivery.class);
            deliveries.put(delivery.getId(), delivery);
        }
    }

    public synchronized PendingDelivery upsert(PendingDelivery delivery) {
        deliveries.put(delivery.getId(), delivery);
        persist();
        return copy(delivery);
    }

    public synchronized Optional<PendingDelivery> find(String id) {
        return Optional.ofNullable(deliveries.get(id)).map(this::copy);
    }

    public synchronized List<PendingDelivery> findAll() {
        List<PendingDelivery> result = new ArrayList<>();
        for (PendingDelivery delivery : deliveries.values()) {
            result.add(copy(delivery));
        }
        return result;
    }

    public synchronized List<PendingDelivery> findBySubscription(String subscriptionId) {
        List<PendingDelivery> result = new ArrayList<>();
        for (PendingDelivery delivery : deliveries.values()) {
            if (delivery.getSubscriptionId().equals(subscriptionId)) {
                result.add(copy(delivery));
            }
        }
        return result;
    }

    public synchronized Optional<PendingDelivery> findPendingByTopic(String subscriptionId, String topic) {
        if (topic == null) {
            return Optional.empty();
        }
        for (PendingDelivery delivery : deliveries.values()) {
            if (delivery.getSubscriptionId().equals(subscriptionId)
                    && delivery.getState() == DeliveryState.PENDING
                    && topic.equals(delivery.getMessage() == null ? null : delivery.getMessage().getTopic())) {
                return Optional.of(copy(delivery));
            }
        }
        return Optional.empty();
    }

    public synchronized boolean delete(String id) {
        if (deliveries.remove(id) == null) {
            return false;
        }
        persist();
        return true;
    }

    public synchronized int deleteBySubscription(String subscriptionId) {
        List<String> toRemove = new ArrayList<>();
        for (PendingDelivery delivery : deliveries.values()) {
            if (delivery.getSubscriptionId().equals(subscriptionId)) {
                toRemove.add(delivery.getId());
            }
        }
        for (String id : toRemove) {
            deliveries.remove(id);
        }
        if (!toRemove.isEmpty()) {
            persist();
        }
        return toRemove.size();
    }

    private void persist() {
        ObjectNode root = JsonFileStore.mapper().createObjectNode();
        for (PendingDelivery delivery : deliveries.values()) {
            root.set(delivery.getId(), JsonFileStore.mapper().valueToTree(delivery));
        }
        fileStore.write(FILE, root);
    }

    private PendingDelivery copy(PendingDelivery delivery) {
        return JsonFileStore.mapper().convertValue(JsonFileStore.mapper().valueToTree(delivery),
                PendingDelivery.class);
    }
}
