package com.github.guoyyit.webpushservicejava.push.web;

import com.github.guoyyit.webpushservicejava.push.service.SubscriptionService;
import com.github.guoyyit.webpushservicejava.push.web.dto.SubscriptionRequest;
import com.github.guoyyit.webpushservicejava.push.web.dto.SubscriptionResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping
    public SubscriptionResponse create(@RequestBody SubscriptionRequest request) {
        return SubscriptionResponse.from(
                subscriptionService.create(request.getEndpoint(), request.getP256dh(), request.getAuth()));
    }

    @GetMapping("/{id}")
    public SubscriptionResponse get(@PathVariable String id) {
        return SubscriptionResponse.from(subscriptionService.get(id));
    }

    @GetMapping
    public List<SubscriptionResponse> list() {
        return subscriptionService.list().stream().map(SubscriptionResponse::from).toList();
    }

    @PutMapping("/{id}")
    public SubscriptionResponse update(@PathVariable String id, @RequestBody SubscriptionRequest request) {
        return SubscriptionResponse.from(
                subscriptionService.update(id, request.getEndpoint(), request.getP256dh(), request.getAuth()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        subscriptionService.delete(id);
    }
}
