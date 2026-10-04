package com.github.guoyyit.webpushservicejava.persistence;

import com.github.guoyyit.webpushservicejava.domain.Delivery;
import com.github.guoyyit.webpushservicejava.domain.Subscription;
import com.github.guoyyit.webpushservicejava.crypto.EcKeyUtils;
import com.github.guoyyit.webpushservicejava.config.PushProperties;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.util.List;

@Service
public class PersistenceService {

    private final PushProperties properties;
    private final ObjectMapper objectMapper;

    public PersistenceService(PushProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        ObjectMapper configured = objectMapper.copy();
        configured.registerModule(new JavaTimeModule());
        configured.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper = configured;
        ensureDataDirectory();
    }

    private void ensureDataDirectory() {
        try {
            Files.createDirectories(Path.of(properties.getDataDir()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create data directory " + properties.getDataDir(), e);
        }
    }

    public synchronized void saveSubscriptions(List<Subscription> subscriptions) {
        writeAtomically(file("subscriptions.json"), subscriptions);
    }

    public synchronized List<Subscription> loadSubscriptions() {
        return readList(file("subscriptions.json"), Subscription.class);
    }

    public synchronized void saveDeliveries(List<Delivery> deliveries) {
        writeAtomically(file("deliveries.json"), deliveries);
    }

    public synchronized List<Delivery> loadDeliveries() {
        return readList(file("deliveries.json"), Delivery.class);
    }

    public synchronized void saveVapidKey(KeyPair keyPair) {
        StoredVapidKey stored = new StoredVapidKey();
        stored.publicKey = java.util.Base64.getEncoder()
                .encodeToString(EcKeyUtils.encodeUncompressedPublic(keyPair.getPublic()));
        stored.privateKeyPkcs8 = java.util.Base64.getEncoder()
                .encodeToString(EcKeyUtils.encodePkcs8(keyPair.getPrivate()));
        writeAtomically(file("vapid-key.json"), stored);
    }

    public synchronized KeyPair loadVapidKey() {
        Path path = file("vapid-key.json");
        if (!Files.exists(path)) {
            return null;
        }
        try {
            StoredVapidKey stored = objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8),
                    StoredVapidKey.class);
            java.security.PublicKey publicKey = EcKeyUtils.decodeUncompressedPublic(
                    java.util.Base64.getDecoder().decode(stored.publicKey));
            java.security.PrivateKey privateKey = EcKeyUtils.decodePkcs8(
                    java.util.Base64.getDecoder().decode(stored.privateKeyPkcs8));
            return new KeyPair(publicKey, privateKey);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load VAPID key", e);
        }
    }

    private Path file(String name) {
        return Path.of(properties.getDataDir(), name);
    }

    private void writeAtomically(Path target, Object value) {
        ensureDataDirectory();
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create directory " + parent, e);
            }
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            try (FileLock ignored = channel.lock()) {
                byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(value);
                channel.truncate(0);
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + target, e);
        }
        try {
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            try {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot replace " + target, e);
            }
        }
    }

    private <T> List<T> readList(Path path, Class<T> type) {
        if (!Files.exists(path)) {
            return new java.util.ArrayList<>();
        }
        try {
            CollectionType collectionType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, type);
            List<T> result = objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), collectionType);
            return result == null ? new java.util.ArrayList<>() : result;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }

    public static class StoredVapidKey {
        public String publicKey;
        public String privateKeyPkcs8;
    }
}
