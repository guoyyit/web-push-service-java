package com.github.guoyyit.webpushservicejava.mockendpoint;

import com.github.guoyyit.webpushservicejava.crypto.CryptoException;
import com.github.guoyyit.webpushservicejava.mockendpoint.MockEndpointRegistry.EndpointRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/mock")
public class MockEndpointController {

    private final MockEndpointRegistry registry;

    public MockEndpointController(MockEndpointRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/endpoints")
    public Map<String, Object> register() {
        EndpointRecord record = registry.register();
        Map<String, Object> response = registry.describe(record);
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        response.put("endpointUrl", baseUrl + "/push/" + record.endpointId);
        return response;
    }

    @GetMapping("/endpoints")
    public List<Map<String, Object>> list() {
        return registry.listRecords();
    }

    @GetMapping("/endpoints/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        EndpointRecord record = registry.requireRecord(id);
        synchronized (record.receivedMessages) {
            Map<String, Object> description = registry.describe(record);
            description.put("messages", List.copyOf(record.receivedMessages));
            return description;
        }
    }

    @DeleteMapping("/endpoints/{id}")
    public ResponseEntity<Void> remove(@PathVariable String id) {
        registry.remove(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/endpoints/{id}/failures")
    public Map<String, Object> configureFailures(@PathVariable String id,
                                                 @RequestParam(defaultValue = "TEMPORARY") String mode,
                                                 @RequestParam(defaultValue = "3") int remaining) {
        MockEndpointRegistry.FailMode failMode;
        try {
            failMode = MockEndpointRegistry.FailMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown fail mode: " + mode);
        }
        registry.setFailMode(id, failMode, remaining);
        return registry.describe(registry.requireRecord(id));
    }

    @PostMapping("/endpoints/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable String id) {
        registry.revoke(id);
        return registry.describe(registry.requireRecord(id));
    }

    @DeleteMapping("/endpoints/{id}/messages")
    public ResponseEntity<Void> clearMessages(@PathVariable String id) {
        registry.clearMessages(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/endpoints/{id}/decrypt")
    public ResponseEntity<?> decrypt(@PathVariable String id,
                                     @RequestParam(defaultValue = "0") int messageIndex,
                                     @RequestParam(defaultValue = "push-message") String expectedAssociatedData) {
        EndpointRecord record = registry.requireRecord(id);
        synchronized (record.receivedMessages) {
            if (messageIndex < 0 || messageIndex >= record.receivedMessages.size()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No message at index " + messageIndex));
            }
            try {
                String plaintext = registry.decryptMessage(record,
                        record.receivedMessages.get(messageIndex), expectedAssociatedData);
                return ResponseEntity.ok(Map.of("plaintext", plaintext));
            } catch (CryptoException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "errorCode", e.getErrorCode().name(),
                        "message", e.getMessage()));
            }
        }
    }

    @PostMapping("/endpoints/{id}/messages/{index}/tamper")
    public Map<String, Object> tamperMessage(@PathVariable String id,
                                             @PathVariable int index,
                                             @RequestParam(defaultValue = "10") int byteIndex) {
        EndpointRecord record = registry.requireRecord(id);
        synchronized (record.receivedMessages) {
            if (index < 0 || index >= record.receivedMessages.size()) {
                throw new IllegalArgumentException("No message at index " + index);
            }
            Map<String, Object> message = record.receivedMessages.get(index);
            String body = (String) message.get("body");
            try {
                com.fasterxml.jackson.databind.node.ObjectNode node =
                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                byte[] ciphertext = java.util.Base64.getUrlDecoder()
                        .decode(node.get("ciphertext").asText());
                int position = Math.min(byteIndex, ciphertext.length - 1);
                ciphertext[position] ^= 0x01;
                node.put("ciphertext", java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(ciphertext));
                message.put("body", node.toString());
                return Map.of("tampered", true, "byteIndex", position);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to tamper message", e);
            }
        }
    }

}
