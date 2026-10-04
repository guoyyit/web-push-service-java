package com.github.guoyyit.webpushservicejava.push.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class JsonFileStore {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private final Path directory;

    public JsonFileStore(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create data directory " + directory, e);
        }
    }

    public synchronized ObjectNode read(String name) {
        Path file = file(name);
        if (!Files.exists(file)) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(Files.readAllBytes(file));
            if (node instanceof ObjectNode objectNode) {
                return objectNode;
            }
            throw new IllegalStateException("store file " + file + " does not contain a JSON object");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read store file " + file, e);
        }
    }

    public synchronized void write(String name, ObjectNode data) {
        Path file = file(name);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(temp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write store file " + file, e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private Path file(String name) {
        return directory.resolve(name + ".json");
    }
}
