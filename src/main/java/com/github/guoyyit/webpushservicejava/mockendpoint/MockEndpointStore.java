package com.github.guoyyit.webpushservicejava.mockendpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.webpushservicejava.config.PushProperties;
import com.github.guoyyit.webpushservicejava.crypto.EcKeyUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MockEndpointStore {

    private final Path file;
    private final ObjectMapper objectMapper;

    public MockEndpointStore(PushProperties properties, ObjectMapper objectMapper) {
        this.file = Path.of(properties.getDataDir(), "mock-endpoints.json");
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public List<MockEndpointRegistry.EndpointRecord> load() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> entries = objectMapper.readValue(
                    Files.readString(file, StandardCharsets.UTF_8), List.class);
            List<MockEndpointRegistry.EndpointRecord> records = new ArrayList<>();
            for (Map<String, Object> entry : entries) {
                records.add(toRecord(entry));
            }
            return records;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load mock endpoints", e);
        }
    }

    public void save(List<MockEndpointRegistry.EndpointRecord> records) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (MockEndpointRegistry.EndpointRecord record : records) {
                entries.add(toMap(record));
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(entries), StandardCharsets.UTF_8);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot save mock endpoints", e);
        }
    }

    private Map<String, Object> toMap(MockEndpointRegistry.EndpointRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("endpointId", record.endpointId);
        map.put("publicKey", Base64.getEncoder()
                .encodeToString(EcKeyUtils.encodeUncompressedPublic(record.keyPair.getPublic())));
        map.put("privateKeyPkcs8", Base64.getEncoder()
                .encodeToString(EcKeyUtils.encodePkcs8(record.keyPair.getPrivate())));
        map.put("authSecret", Base64.getEncoder().encodeToString(record.authSecret));
        map.put("failMode", record.failMode.name());
        map.put("failRemaining", record.failRemaining);
        map.put("revoked", record.revoked);
        map.put("createdAt", record.createdAt.toString());
        synchronized (record.receivedMessages) {
            map.put("receivedMessages", List.copyOf(record.receivedMessages));
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private MockEndpointRegistry.EndpointRecord toRecord(Map<String, Object> entry) {
        MockEndpointRegistry.EndpointRecord record = new MockEndpointRegistry.EndpointRecord();
        record.endpointId = (String) entry.get("endpointId");
        PublicKey publicKey = EcKeyUtils.decodeUncompressedPublic(
                Base64.getDecoder().decode((String) entry.get("publicKey")));
        PrivateKey privateKey = EcKeyUtils.decodePkcs8(
                Base64.getDecoder().decode((String) entry.get("privateKeyPkcs8")));
        record.keyPair = new KeyPair(publicKey, privateKey);
        record.authSecret = Base64.getDecoder().decode((String) entry.get("authSecret"));
        record.failMode = MockEndpointRegistry.FailMode.valueOf((String) entry.get("failMode"));
        Number remaining = (Number) entry.get("failRemaining");
        record.failRemaining = remaining == null ? 0 : remaining.intValue();
        record.revoked = Boolean.TRUE.equals(entry.get("revoked"));
        record.createdAt = Instant.parse((String) entry.get("createdAt"));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) entry.get("receivedMessages");
        if (messages != null) {
            record.receivedMessages.addAll(messages);
        }
        return record;
    }
}
