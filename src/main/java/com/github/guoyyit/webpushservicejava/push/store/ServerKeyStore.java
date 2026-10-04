package com.github.guoyyit.webpushservicejava.push.store;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.webpushservicejava.push.crypto.CryptoUtils;
import com.github.guoyyit.webpushservicejava.push.crypto.VapidService;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

public class ServerKeyStore {

    private static final String FILE = "server-keys";

    private final JsonFileStore fileStore;
    private KeyPair keyPair;
    private long generation;

    public ServerKeyStore(JsonFileStore fileStore) {
        this.fileStore = fileStore;
        load();
    }

    public synchronized KeyPair currentKeyPair() {
        return keyPair;
    }

    public synchronized KeyPair rotate() {
        keyPair = VapidService.generateKeyPair();
        generation++;
        persist();
        return keyPair;
    }

    public synchronized long generation() {
        return generation;
    }

    private void load() {
        ObjectNode root = fileStore.read(FILE);
        if (root.has("publicKey") && root.has("privateKey")) {
            PublicKey publicKey = CryptoUtils.decodeP256PublicKey(root.get("publicKey").asText());
            PrivateKey privateKey = CryptoUtils.decodeP256PrivateKey(root.get("privateKey").asText());
            keyPair = new KeyPair(publicKey, privateKey);
            generation = root.path("generation").asLong(0);
        } else {
            keyPair = VapidService.generateKeyPair();
            generation = 1;
            persist();
        }
    }

    private void persist() {
        ObjectNode root = JsonFileStore.mapper().createObjectNode();
        root.put("generation", generation);
        root.put("publicKey", CryptoUtils.encodePublicKey(keyPair.getPublic()));
        root.put("privateKey", CryptoUtils.encodePrivateKey(keyPair.getPrivate()));
        fileStore.write(FILE, root);
    }
}
