package com.github.guoyyit.webpushservicejava.web;

import com.github.guoyyit.webpushservicejava.delivery.DeliveryScheduler;
import com.github.guoyyit.webpushservicejava.domain.Delivery;
import com.github.guoyyit.webpushservicejava.message.MessageService;
import com.github.guoyyit.webpushservicejava.web.dto.PushSendRequest;
import com.github.guoyyit.webpushservicejava.web.dto.SendResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions/{subscriptionId}")
public class PushController {

    private final MessageService messageService;
    private final DeliveryScheduler deliveryScheduler;

    public PushController(MessageService messageService, DeliveryScheduler deliveryScheduler) {
        this.messageService = messageService;
        this.deliveryScheduler = deliveryScheduler;
    }

    @PostMapping("/push")
    public SendResponse send(@PathVariable String subscriptionId, @RequestBody PushSendRequest request) {
        MessageService.EnqueueResult result = messageService.enqueue(subscriptionId, request);
        return new SendResponse(result.delivery().getId(), result.coalescedDeliveryId());
    }

    @GetMapping("/deliveries")
    public List<Delivery> list(@PathVariable String subscriptionId) {
        return messageService.listForSubscription(subscriptionId);
    }

    @GetMapping("/deliveries/{deliveryId}")
    public Delivery get(@PathVariable String subscriptionId, @PathVariable String deliveryId) {
        Delivery delivery = messageService.require(deliveryId);
        if (!delivery.getSubscriptionId().equals(subscriptionId)) {
            throw new ServiceException(com.github.guoyyit.webpushservicejava.domain.ErrorCode.SUBSCRIPTION_NOT_FOUND,
                    "Delivery does not belong to subscription '" + subscriptionId + "'");
        }
        return delivery;
    }

    @PostMapping("/dispatch-tick")
    public Map<String, Object> tick() {
        deliveryScheduler.tick();
        List<Delivery> all = messageService.listAll();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", all.size());
        response.put("statuses", all.stream().collect(java.util.stream.Collectors
                .groupingBy(delivery -> delivery.getStatus().name(), java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.counting())));
        return response;
    }
}
