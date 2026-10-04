package com.github.guoyyit.webpushservicejava.push.config;

import com.github.guoyyit.webpushservicejava.push.crypto.PayloadCipher;
import com.github.guoyyit.webpushservicejava.push.crypto.VapidService;
import com.github.guoyyit.webpushservicejava.push.endpoint.EndpointRegistry;
import com.github.guoyyit.webpushservicejava.push.service.DeliveryService;
import com.github.guoyyit.webpushservicejava.push.service.PushService;
import com.github.guoyyit.webpushservicejava.push.service.SubscriptionLocks;
import com.github.guoyyit.webpushservicejava.push.service.SubscriptionService;
import com.github.guoyyit.webpushservicejava.push.store.DeliveryStore;
import com.github.guoyyit.webpushservicejava.push.store.JsonFileStore;
import com.github.guoyyit.webpushservicejava.push.store.ServerKeyStore;
import com.github.guoyyit.webpushservicejava.push.store.SubscriptionStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.SmartLifecycle;

import java.nio.file.Path;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(PushProperties.class)
public class PushConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public JsonFileStore jsonFileStore(PushProperties properties) {
        return new JsonFileStore(Path.of(properties.getDataDir()));
    }

    @Bean
    public SubscriptionStore subscriptionStore(JsonFileStore fileStore) {
        return new SubscriptionStore(fileStore);
    }

    @Bean
    public DeliveryStore deliveryStore(JsonFileStore fileStore) {
        return new DeliveryStore(fileStore);
    }

    @Bean
    public ServerKeyStore serverKeyStore(JsonFileStore fileStore) {
        return new ServerKeyStore(fileStore);
    }

    @Bean
    public VapidService vapidService() {
        return new VapidService();
    }

    @Bean
    public PayloadCipher payloadCipher(Clock clock) {
        return new PayloadCipher(clock);
    }

    @Bean
    public EndpointRegistry endpointRegistry() {
        return new EndpointRegistry();
    }

    @Bean
    public SubscriptionLocks subscriptionLocks() {
        return new SubscriptionLocks();
    }

    @Bean
    public SubscriptionService subscriptionService(SubscriptionStore subscriptionStore,
                                                   DeliveryStore deliveryStore,
                                                   SubscriptionLocks subscriptionLocks,
                                                   Clock clock) {
        return new SubscriptionService(subscriptionStore, deliveryStore, subscriptionLocks, clock);
    }

    @Bean
    public DeliveryService deliveryService(SubscriptionStore subscriptionStore, DeliveryStore deliveryStore,
                                           EndpointRegistry endpointRegistry, PayloadCipher payloadCipher,
                                           PushProperties properties, Clock clock,
                                           SubscriptionLocks subscriptionLocks) {
        return new DeliveryService(subscriptionStore, deliveryStore, endpointRegistry, payloadCipher,
                properties, clock, subscriptionLocks);
    }

    @Bean
    public PushService pushService(SubscriptionService subscriptionService, DeliveryService deliveryService,
                                   VapidService vapidService, ServerKeyStore serverKeyStore,
                                   PushProperties properties, Clock clock) {
        return new PushService(subscriptionService, deliveryService, vapidService, serverKeyStore,
                properties, clock);
    }

    @Bean
    public SmartLifecycle deliverySchedulerLifecycle(DeliveryService deliveryService) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                deliveryService.start();
                running = true;
            }

            @Override
            public void stop() {
                deliveryService.stop();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}
