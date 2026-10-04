package com.github.guoyyit.webpushservicejava.mockendpoint;

import com.github.guoyyit.webpushservicejava.crypto.EcKeyUtils;
import com.github.guoyyit.webpushservicejava.crypto.PayloadCryptoService;
import com.github.guoyyit.webpushservicejava.domain.EncryptedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MockEndpointRegistry {

    public enum FailMode {
        NONE, TEMPORARY, PERMANENT
    }

    public static class EndpointRecord {
        public String endpointId;
        public KeyPair keyPair;
        public byte[] authSecret;
        public FailMode failMode = FailMode.NONE;
        public int failRemaining;
        public boolean revoked;
        public Instant createdAt;
        public final List<Map<String, Object>> receivedMessages =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        public String getP256dhBase64() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(EcKeyUtils.encodeUncompressedPublic(keyPair.getPublic()));
        }

        public String getAuthBase64() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(authSecret);
        }
    }

    private final Map<String, EndpointRecord> records = new ConcurrentHashMap<>();
    private final PayloadCryptoService payloadCryptoService;
    private final ObjectMapper objectMapper;
    private final MockEndpointStore store;

    public MockEndpointRegistry(PayloadCryptoService payloadCryptoService, ObjectMapper objectMapper,
                                MockEndpointStore store) {
        this.payloadCryptoService = payloadCryptoService;
        this.objectMapper = objectMapper;
        this.store = store;
        for (EndpointRecord record : store.load()) {
            records.put(record.endpointId, record);
        }
    }

    public EndpointRecord register() {
        EndpointRecord record = new EndpointRecord();
        record.endpointId = UUID.randomUUID().toString();
        record.keyPair = EcKeyUtils.generateP256KeyPair();
        record.authSecret = new byte[16];
        new java.security.SecureRandom().nextBytes(record.authSecret);
        record.createdAt = Instant.now();
        records.put(record.endpointId, record);
        persist();
        return record;
    }

    public EndpointRecord getRecord(String endpointId) {
        return records.get(endpointId);
    }

    public EndpointRecord requireRecord(String endpointId) {
        EndpointRecord record = records.get(endpointId);
        if (record == null) {
            throw new IllegalArgumentException("Unknown endpoint: " + endpointId);
        }
        return record;
    }

    public List<Map<String, Object>> listRecords() {
        return records.values().stream().map(this::describe).toList();
    }

    public Map<String, Object> describe(EndpointRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("endpointId", record.endpointId);
        map.put("endpointUrl", "/push/" + record.endpointId);
        map.put("p256dh", record.getP256dhBase64());
        map.put("auth", record.getAuthBase64());
        map.put("failMode", record.failMode.name());
        map.put("failRemaining", record.failRemaining);
        map.put("revoked", record.revoked);
        map.put("createdAt", record.createdAt.toString());
        synchronized (record.receivedMessages) {
            map.put("messageCount", record.receivedMessages.size());
        }
        return map;
    }

    public EndpointRecord remove(String endpointId) {
        EndpointRecord removed = records.remove(endpointId);
        if (removed != null) {
            persist();
        }
        return removed;
    }

    public void setFailMode(String endpointId, FailMode mode, int remaining) {
        EndpointRecord record = requireRecord(endpointId);
        record.failMode = mode;
        record.failRemaining = mode == FailMode.TEMPORARY ? remaining : 0;
        if (mode == FailMode.PERMANENT) {
            record.failRemaining = Integer.MAX_VALUE;
        }
        persist();
    }

    public void revoke(String endpointId) {
        EndpointRecord record = requireRecord(endpointId);
        record.revoked = true;
        persist();
    }

    public void clearMessages(String endpointId) {
        EndpointRecord record = requireRecord(endpointId);
        record.receivedMessages.clear();
        persist();
    }

    public synchronized int recordPush(String endpointId, Map<String, Object> headers, String bodyJson) {
        EndpointRecord record = requireRecord(endpointId);
        if (record.revoked) {
            return 410;
        }
        if (record.failMode == FailMode.PERMANENT) {
            return 410;
        }
        if (record.failMode == FailMode.TEMPORARY) {
            if (record.failRemaining > 0) {
                record.failRemaining--;
                return 503;
            }
            record.failMode = FailMode.NONE;
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("receivedAt", Instant.now().toString());
        message.put("headers", headers);
        message.put("body", bodyJson);
        record.receivedMessages.add(message);
        persist();
        return 201;
    }

    private void persist() {
        store.save(List.copyOf(records.values()));
    }

    @SuppressWarnings("unchecked")
    public String decryptMessage(EndpointRecord record, Map<String, Object> storedMessage,
                                 String expectedAssociatedData) {
        String bodyJson = (String) storedMessage.get("body");
        EncryptedPayload payload;
        try {
            payload = objectMapper.readValue(bodyJson, EncryptedPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored body is not an EncryptedPayload", e);
        }
        return payloadCryptoService.decrypt(payload, record.keyPair, record.authSecret,
                expectedAssociatedData);
    }
}
