package com.github.guoyyit.webpushservicejava.push.web;

import com.github.guoyyit.webpushservicejava.push.service.PushService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/keys")
public class KeyController {

    private final PushService pushService;

    public KeyController(PushService pushService) {
        this.pushService = pushService;
    }

    @GetMapping("/public")
    public Map<String, String> publicKey() {
        return Map.of("publicKey", pushService.publicKey());
    }

    @PostMapping("/rotate")
    public Map<String, Object> rotate() {
        long generation = pushService.rotateKeys();
        return Map.of("generation", generation, "publicKey", pushService.publicKey());
    }

    @PostMapping("/token")
    public Map<String, String> issueToken() {
        return Map.of("token", pushService.issueVapidToken());
    }
}
