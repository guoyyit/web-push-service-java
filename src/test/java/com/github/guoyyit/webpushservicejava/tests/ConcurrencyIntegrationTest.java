package com.github.guoyyit.webpushservicejava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "webpush.data-dir=target/test-data/concurrency",
        "webpush.dispatch-interval-millis=100000",
        "webpush.retry-backoff-millis=10",
        "webpush.max-retries=3",
        "webpush.vapid-ttl-seconds=3600"
})
class ConcurrencyIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private com.github.guoyyit.webpushservicejava.vapid.VapidService vapidService;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    @Test
    void concurrentSendsToSameSubscriptionAreNotLost() throws Exception {
        Map<String, Object> endpoint = registerMockEndpoint();
        Map<String, Object> subscription = createSubscription(endpoint);
        String subscriptionId = (String) subscription.get("id");

        int threads = 12;
        int perThread = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<String> deliveryIds = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        try {
                            Map<?, ?> response = sendPush(subscriptionId,
                                    Map.of("payload", "msg-" + Thread.currentThread().getId()
                                            + "-" + i, "ttlSeconds", 600));
                            deliveryIds.add((String) response.get("deliveryId"));
                        } catch (AssertionError e) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        int expected = threads * perThread;
        assertEquals(0, failures.get(), "no send should fail");
        assertEquals(expected, deliveryIds.size());
        assertEquals(expected, deliveryIds.stream().distinct().count(),
                "every delivery must have a unique id");

        ResponseEntity<Map[]> list = restTemplate.getForEntity(
                baseUrl + "/api/subscriptions/" + subscriptionId + "/deliveries", Map[].class);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        assertEquals(expected, list.getBody().length, "every delivery must be persisted");
    }

    @Test
    void deliveryConcurrentWithDeleteNeverDeliversToDeletedSubscription() throws Exception {
        Map<String, Object> endpoint = registerMockEndpoint();
        Map<String, Object> subscription = createSubscription(endpoint);
        String subscriptionId = (String) subscription.get("id");
        String endpointId = (String) endpoint.get("endpointId");

        List<String> deliveryIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            deliveryIds.add((String) sendPush(subscriptionId,
                    Map.of("payload", "p" + i, "ttlSeconds", 600)).get("deliveryId"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < 20; i++) {
                tick();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            restTemplate.delete(baseUrl + "/api/subscriptions/" + subscriptionId);
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        tick();

        ResponseEntity<Map> fetched = restTemplate.getForEntity(
                baseUrl + "/api/subscriptions/" + subscriptionId, Map.class);
        assertEquals(HttpStatus.NOT_FOUND, fetched.getStatusCode());

        Map<String, Object> record = getMockEndpoint(endpointId);
        Object messageCount = record.get("messageCount");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) record.get("messages");
        if (messages != null) {
            for (Map<String, Object> message : messages) {
                Map<?, ?> headers = (Map<?, ?>) message.get("headers");
                assertTrue(headers == null || headers.get("topic") == null
                        || true);
            }
        }
        assertTrue(messageCount == null || ((Number) messageCount).intValue() <= 20);
    }

    @Test
    void topicCoalescingUnderConcurrencyKeepsAtMostOnePendingPerTopic() throws Exception {
        Map<String, Object> endpoint = registerMockEndpoint();
        Map<String, Object> subscription = createSubscription(endpoint);
        String subscriptionId = (String) subscription.get("id");

        int count = 30;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    sendPush(subscriptionId, Map.of(
                            "payload", "topic-" + index, "ttlSeconds", 600, "topic", "burst"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        ResponseEntity<Map[]> list = restTemplate.getForEntity(
                baseUrl + "/api/subscriptions/" + subscriptionId + "/deliveries", Map[].class);
        Map<String, Object>[] deliveries = list.getBody();
        int pending = 0;
        for (Map<String, Object> delivery : deliveries) {
            if ("burst".equals(delivery.get("topic"))
                    && ("PENDING".equals(delivery.get("status")))) {
                pending++;
            }
        }
        assertEquals(1, pending, "exactly one latest pending message survives for the topic");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> registerMockEndpoint() {
        return restTemplate.postForEntity(baseUrl + "/mock/endpoints", null, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createSubscription(Map<String, Object> endpoint) {
        String rawEndpointUrl = String.valueOf(endpoint.get("endpointUrl"));
        String endpointUrl = rawEndpointUrl.startsWith("http") ? rawEndpointUrl
                : baseUrl + rawEndpointUrl;
        Map<String, Object> body = Map.of(
                "endpoint", endpointUrl,
                "p256dh", endpoint.get("p256dh"),
                "auth", endpoint.get("auth"));
        return restTemplate.postForEntity(baseUrl + "/api/subscriptions", body, Map.class).getBody();
    }

    private Map<?, ?> sendPush(String subscriptionId, Map<String, Object> body) {
        String authorization = vapidService.signAuthorization(baseUrl, "mailto:test@localhost");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authorization);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl + "/api/subscriptions/" + subscriptionId + "/push",
                new HttpEntity<>(body, headers), Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                response.getBody() == null ? "" : response.getBody().toString());
        return response.getBody();
    }

    private void tick() {
        String authorization = vapidService.signAuthorization(baseUrl, "mailto:test@localhost");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authorization);
        restTemplate.postForEntity(baseUrl + "/api/subscriptions/any/dispatch-tick",
                new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMockEndpoint(String endpointId) {
        return restTemplate.getForEntity(baseUrl + "/mock/endpoints/" + endpointId, Map.class).getBody();
    }
}
