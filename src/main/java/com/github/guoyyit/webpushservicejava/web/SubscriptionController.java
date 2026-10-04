package com.github.guoyyit.webpushservicejava.web;

import com.github.guoyyit.webpushservicejava.domain.Subscription;
import com.github.guoyyit.webpushservicejava.subscription.SubscriptionService;
import com.github.guoyyit.webpushservicejava.vapid.VapidService;
import com.github.guoyyit.webpushservicejava.web.dto.SubscriptionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final VapidService vapidService;

    public SubscriptionController(SubscriptionService subscriptionService, VapidService vapidService) {
        this.subscriptionService = subscriptionService;
        this.vapidService = vapidService;
    }

    @GetMapping("/vapid/public-key")
    public Map<String, String> vapidPublicKey() {
        return Map.of("publicKey", vapidService.getPublicKeyBase64());
    }

    @PostMapping("/vapid/rotate")
    public Map<String, String> rotateVapidKey() {
        vapidService.rotate();
        return Map.of("publicKey", vapidService.getPublicKeyBase64());
    }

    @PostMapping("/subscriptions")
    public Subscription create(@RequestBody SubscriptionRequest request) {
        return subscriptionService.create(request);
    }

    @PutMapping("/subscriptions/{id}")
    public Subscription update(@PathVariable String id, @RequestBody SubscriptionRequest request) {
        return subscriptionService.update(id, request);
    }

    @GetMapping("/subscriptions")
    public Collection<Subscription> list() {
        return subscriptionService.list();
    }

    @GetMapping("/subscriptions/{id}")
    public Subscription get(@PathVariable String id) {
        return subscriptionService.require(id);
    }

    @DeleteMapping("/subscriptions/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        subscriptionService.require(id);
        subscriptionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
