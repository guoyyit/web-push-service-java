package com.github.guoyyit.webpushservicejava.mockendpoint;

import com.github.guoyyit.webpushservicejava.mockendpoint.MockEndpointRegistry.EndpointRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class PushEndpointController {

    private final MockEndpointRegistry registry;

    public PushEndpointController(MockEndpointRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/push/{id}")
    public ResponseEntity<Map<String, Object>> receivePush(@PathVariable String id,
                                                           @RequestBody(required = false) String body,
                                                           HttpServletRequest request) {
        EndpointRecord existing = registry.getRecord(id);
        if (existing == null || existing.revoked) {
            return gone();
        }
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("authorization", request.getHeader("Authorization"));
        headers.put("ttl", request.getHeader("TTL"));
        headers.put("urgency", request.getHeader("Urgency"));
        headers.put("topic", request.getHeader("Topic"));

        int status = registry.recordPush(id, headers, body);
        return switch (status) {
            case 201 -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("status", "accepted"));
            case 503 -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "1")
                    .body(Map.of("status", "temporary-failure"));
            default -> gone();
        };
    }

    private ResponseEntity<Map<String, Object>> gone() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("status", "endpoint-gone"));
    }
}
