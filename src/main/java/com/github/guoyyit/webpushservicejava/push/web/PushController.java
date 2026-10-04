package com.github.guoyyit.webpushservicejava.push.web;

import com.github.guoyyit.webpushservicejava.push.exception.ErrorCode;
import com.github.guoyyit.webpushservicejava.push.exception.PushException;
import com.github.guoyyit.webpushservicejava.push.model.PendingDelivery;
import com.github.guoyyit.webpushservicejava.push.model.PushMessage;
import com.github.guoyyit.webpushservicejava.push.model.Urgency;
import com.github.guoyyit.webpushservicejava.push.service.PushService;
import com.github.guoyyit.webpushservicejava.push.web.dto.PushRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushService pushService;

    public PushController(PushService pushService) {
        this.pushService = pushService;
    }

    @PostMapping
    public PendingDelivery send(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                @RequestBody PushRequest request) {
        if (request.getSubscriptionId() == null || request.getSubscriptionId().isBlank()) {
            throw new PushException(ErrorCode.INVALID_REQUEST, "subscriptionId is required");
        }
        PushMessage message = new PushMessage();
        message.setPayload(request.getPayload());
        message.setTtlSeconds(request.getTtlSeconds() == null ? 0 : request.getTtlSeconds());
        try {
            message.setUrgency(Urgency.fromString(request.getUrgency()));
        } catch (IllegalArgumentException e) {
            throw new PushException(ErrorCode.INVALID_REQUEST, e.getMessage(), e);
        }
        message.setTopic(request.getTopic());
        return pushService.send(authorization, request.getSubscriptionId(), message);
    }
}
