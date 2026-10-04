package com.github.guoyyit.webpushservicejava.push.endpoint;

import com.github.guoyyit.webpushservicejava.push.crypto.EncryptedPayload;
import com.github.guoyyit.webpushservicejava.push.model.EndpointResult;
import com.github.guoyyit.webpushservicejava.push.model.Urgency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class EndpointRegistry {

    public static final String SCHEME = "endpoint://";
    private static final Pattern ENDPOINT_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    public record ReceivedMessage(EncryptedPayload payload, Urgency urgency, String topic) {
    }

    private static final class EndpointState {
        private final List<ReceivedMessage> received = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger transientFailuresRemaining = new AtomicInteger();
        private volatile boolean revoked;
        private volatile EndpointResult lastResult;
    }

    private final Map<String, EndpointState> endpoints = new ConcurrentHashMap<>();

    public EndpointRegistry() {
    }

    public String register() {
        String endpoint = SCHEME + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        endpoints.put(endpoint, new EndpointState());
        return endpoint;
    }

    public boolean exists(String endpoint) {
        return endpoints.containsKey(endpoint);
    }

    public void revoke(String endpoint) {
        EndpointState state = endpoints.get(endpoint);
        if (state != null) {
            state.revoked = true;
        }
    }

    public void failTransiently(String endpoint, int times) {
        EndpointState state = endpoints.get(endpoint);
        if (state != null) {
            state.transientFailuresRemaining.addAndGet(times);
        }
    }

    public EndpointResult deliver(String endpoint, EncryptedPayload payload, Urgency urgency, String topic) {
        EndpointState state = endpoints.get(endpoint);
        if (state == null) {
            return EndpointResult.NOT_FOUND;
        }
        EndpointResult result;
        if (state.revoked) {
            result = EndpointResult.REVOKED;
        } else if (state.transientFailuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            result = EndpointResult.TRANSIENT_FAILURE;
        } else {
            state.received.add(new ReceivedMessage(payload, urgency, topic));
            result = EndpointResult.DELIVERED;
        }
        state.lastResult = result;
        return result;
    }

    public List<ReceivedMessage> received(String endpoint) {
        EndpointState state = endpoints.get(endpoint);
        if (state == null) {
            return List.of();
        }
        synchronized (state.received) {
            return List.copyOf(state.received);
        }
    }

    public Optional<EndpointResult> lastResult(String endpoint) {
        EndpointState state = endpoints.get(endpoint);
        return state == null ? Optional.empty() : Optional.ofNullable(state.lastResult);
    }

    public static boolean isValidFormat(String endpoint) {
        if (endpoint == null || !endpoint.startsWith(SCHEME)) {
            return false;
        }
        return ENDPOINT_ID_PATTERN.matcher(endpoint.substring(SCHEME.length())).matches();
    }
}
