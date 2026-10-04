package com.github.guoyyit.webpushservicejava.push.web;

import com.github.guoyyit.webpushservicejava.push.endpoint.EndpointRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mock-endpoints")
public class MockEndpointController {

    private final EndpointRegistry registry;

    public MockEndpointController(EndpointRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    public Map<String, String> register() {
        String endpoint = registry.register();
        return Map.of("endpoint", endpoint);
    }

    @GetMapping("/{id}/messages")
    public List<EndpointRegistry.ReceivedMessage> messages(@PathVariable String id) {
        return registry.received(EndpointRegistry.SCHEME + id);
    }
}
