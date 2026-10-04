package com.github.guoyyit.webpushservicejava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "webpush.data-dir=target/test-data/e2e",
        "webpush.dispatch-interval-millis=100000",
        "webpush.retry-backoff-millis=30",
        "webpush.max-retries=3",
        "webpush.vapid-ttl-seconds=3600"
})
class EndToEndIntegrationTest {

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
    void fullDeliveryLoopEncryptsDeliversAndDecrypts() {
        Map<String, Object> endpoint = registerMockEndpoint();
        String endpointId = (String) endpoint.get("endpointId");
        Map<String, Object> subscription = createSubscription(endpoint);
        String subscriptionId = (String) subscription.get("id");

        Map<String, Object> sendBody = Map.of(
                "payload", "hello-e2e",
                "ttlSeconds", 300,
                "urgency", "HIGH");
        Map<?, ?> sendResponse = sendPush(subscriptionId, sendBody);
        String deliveryId = (String) sendResponse.get("deliveryId");

        tick();

        Map<String, Object> delivery = getDelivery(subscriptionId, deliveryId);
        assertEquals("SENT", delivery.get("status"));

        Map<String, Object> record = getMockEndpoint(endpointId);
        assertEquals(1, ((Number) record.get("messageCount")).intValue());

        ResponseEntity<Map> decrypted = restTemplate.postForEntity(
                org.springframework.web.util.UriComponentsBuilder
                        .fromHttpUrl(baseUrl + "/mock/endpoints/" + endpointId + "/decrypt")
                        .queryParam("messageIndex", 0)
                        .queryParam("expectedAssociatedData", "push-message:" + deliveryId)
                        .build().toUri(),
                null, Map.class);
        assertEquals(HttpStatus.OK, decrypted.getStatusCode());
        assertEquals("hello-e2e", decrypted.getBody().get("plaintext"));
    }

    @Test
    void tamperedMessageFailsDecryptionAtEndpoint() {
        Map<String, Object> endpoint = registerMockEndpoint();
        String endpointId = (String) endpoint.get("endpointId");
        Map<String, Object> subscription = createSubscription(endpoint);
        String subscriptionId = (String) subscription.get("id");

        Map<?, ?> sendResponse = sendPush(subscriptionId, Map.of(
                "payload", "tamper-target", "ttlSeconds", 300));
        String deliveryId = (String) sendResponse.get("deliveryId");
        tick();
        assertEquals("SENT", getDelivery(subscriptionId, deliveryId).get("status"));

        restTemplate.postForEntity(baseUrl + "/mock/endpoints/" + endpointId
                + "/messages/0/tamper?byteIndex=10", null, Map.class);

        org.springframework.web.util.UriComponentsBuilder decryptUrl =
                org.springframework.web.util.UriComponentsBuilder
                        .fromHttpUrl(baseUrl + "/mock/endpoints/" + endpointId + "/decrypt")
                        .queryParam("messageIndex", 0)
                        .queryParam("expectedAssociatedData", "push-message:" + deliveryId);
        ResponseEntity<Map> decrypted = restTemplate.postForEntity(
                decryptUrl.build().toUri(), null, Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, decrypted.getStatusCode());
        assertEquals("DECRYPT_TAMPERED", decrypted.getBody().get("errorCode"));
    }

    @Test
    void expiredMessageIsNotDeliveredWithDistinctStatus() {
        Map<String, Object> endpoint = registerMockEndpoint();
        Map<String, Object> subscription = createSubscription(endpoint);
        String subscriptionId = (String) subscription.get("id");

        Map<String, Object> sendBody = Map.of(
                "payload", "doomed",
                "ttlSeconds", 0);
        Map<?, ?> sendResponse = sendPush(subscriptionId, sendBody);
        String deliveryId = (String) sendResponse.get("deliveryId");

        try {
            Thread.sleep(1_100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        tick();

        Map<String, Object> delivery = getDelivery(subscriptionId, deliveryId);
        assertEquals("EXPIRED", delivery.get("status"));
        String lastError = (String) delivery.get("lastError");
        assertTrue(lastError != null && lastError.contains("TTL"));
    }

    @Test
    void sameTopicKeepsOnlyLatestMessage() {
        Map<String, Object> endpoint = registerMockEndpoint();
        Map<String, Object> subscription = createSubscription(endpoint);
        String subscriptionId = (String) subscription.get("id");

        Map<?, ?> first = sendPush(subscriptionId, Map.of(
                "payload", "first", "ttlSeconds", 300, "topic", "news"));
        Map<?, ?> second = sendPush(subscriptionId, Map.of(
                "payload", "second", "ttlSeconds", 300, "topic", "news"));

        String coalescedId = (String) second.get("coalescedDeliveryId");
        assertEquals(first.get("deliveryId"), coalescedId);

        Map<String, Object> oldDelivery = getDelivery(subscriptionId, coalescedId);
        assertEquals("COALESCED", oldDelivery.get("status"));
    }

    @Test
    void temporaryFailuresAreRetriedThenSucceed() {
        Map<String, Object> endpoint = registerMockEndpoint();
        String endpointId = (String) endpoint.get("endpointId");
        configureFailure(endpointId, "TEMPORARY", 2);

        Map<String, Object> subscription = createSubscription(endpoint);
        String subscriptionId = (String) subscription.get("id");

        Map<?, ?> sendResponse = sendPush(subscriptionId, Map.of(
                "payload", "retry-me", "ttlSeconds", 300));
        String deliveryId = (String) sendResponse.get("deliveryId");

        tick();
        Map<String, Object> afterFirstTick = getDelivery(subscriptionId, deliveryId);
        assertEquals("RETRY_WAIT", afterFirstTick.get("status"));
        assertEquals(1, ((Number) afterFirstTick.get("attempts")).intValue());

        waitUntilStatus(subscriptionId, deliveryId, "SENT", 10_000);
        Map<String, Object> delivery = getDelivery(subscriptionId, deliveryId);
        assertTrue(((Number) delivery.get("attempts")).intValue() >= 3);
    }

    @Test
    void permanentFailureCleansUpSubscription() {
        Map<String, Object> endpoint = registerMockEndpoint();
        String endpointId = (String) endpoint.get("endpointId");
        configureFailure(endpointId, "PERMANENT", 0);

        Map<String, Object> subscription = createSubscription(endpoint);
        String subscriptionId = (String) subscription.get("id");

        Map<?, ?> sendResponse = sendPush(subscriptionId, Map.of(
                "payload", "gone", "ttlSeconds", 300));
        String deliveryId = (String) sendResponse.get("deliveryId");

        tick();

        Map<String, Object> delivery = getDelivery(subscriptionId, deliveryId);
        assertEquals("FAILED_PERMANENT", delivery.get("status"));

        ResponseEntity<Map> response = restTemplate.getForEntity(
                baseUrl + "/api/subscriptions/" + subscriptionId, Map.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void retryBudgetIsExhaustedAfterConfiguredLimit() {
        Map<String, Object> endpoint = registerMockEndpoint();
        String endpointId = (String) endpoint.get("endpointId");
        configureFailure(endpointId, "TEMPORARY", 100);

        Map<String, Object> subscription = createSubscription(endpoint);
        String subscriptionId = (String) subscription.get("id");

        Map<?, ?> sendResponse = sendPush(subscriptionId, Map.of(
                "payload", "always-fails", "ttlSeconds", 3600));
        String deliveryId = (String) sendResponse.get("deliveryId");

        waitUntilStatus(subscriptionId, deliveryId, "FAILED_RETRIES_EXHAUSTED", 10_000);
        Map<String, Object> delivery = getDelivery(subscriptionId, deliveryId);
        assertEquals(3, ((Number) delivery.get("attempts")).intValue());
        assertTrue(String.valueOf(delivery.get("lastError")).contains("retry budget exhausted"));
    }

    @Test
    void pushWithoutVapidAuthorizationIsRejected() {
        Map<String, Object> endpoint = registerMockEndpoint();
        Map<String, Object> subscription = createSubscription(endpoint);
        String subscriptionId = (String) subscription.get("id");

        HttpHeaders headers = new HttpHeaders();
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of("payload", "x"), headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl + "/api/subscriptions/" + subscriptionId + "/push", entity, Map.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("VAPID_MISSING", response.getBody().get("errorCode"));
    }

    @Test
    void invalidSubscriptionInputReturnsDistinctErrors() {
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<Map<String, Object>> badEndpoint = new HttpEntity<>(Map.of(
                "endpoint", "ftp://nope/x",
                "p256dh", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "auth", "AAAAAAAAAAAAAAAAAAAAAA=="), headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl + "/api/subscriptions", badEndpoint, Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_ENDPOINT", response.getBody().get("errorCode"));
    }

    @Test
    void oldVapidTokenIsRejectedAfterServerKeyRotation() {
        Map<String, Object> endpoint = registerMockEndpoint();
        Map<String, Object> subscription = createSubscription(endpoint);
        String subscriptionId = (String) subscription.get("id");

        String oldToken = vapidService.signAuthorization(baseUrl, "mailto:old@localhost");
        org.springframework.http.ResponseEntity<Map> rotation = rotateKey(oldToken);
        assertEquals(HttpStatus.OK, rotation.getStatusCode());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", oldToken);
        org.springframework.http.ResponseEntity<Map> rejected = restTemplate.exchange(
                baseUrl + "/api/subscriptions/" + subscriptionId + "/push",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("payload", "stale-cred"), headers),
                Map.class);
        assertEquals(HttpStatus.FORBIDDEN, rejected.getStatusCode());
        assertEquals("VAPID_BAD_SIGNATURE", rejected.getBody().get("errorCode"));
    }

    private org.springframework.http.ResponseEntity<Map> rotateKey(String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authorization);
        return restTemplate.exchange(baseUrl + "/api/vapid/rotate",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);
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
        String audience = baseUrl;
        String authorization = vapidService.signAuthorization(audience, "mailto:test@localhost");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authorization);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl + "/api/subscriptions/" + subscriptionId + "/push", entity, Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody() == null
                ? "no body" : response.getBody().toString());
        return response.getBody();
    }

    private void tick() {
        String authorization = vapidService.signAuthorization(baseUrl, "mailto:test@localhost");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authorization);
        restTemplate.postForEntity(baseUrl + "/api/subscriptions/any/dispatch-tick",
                new HttpEntity<>(headers), Map.class);
    }

    private void configureFailure(String endpointId, String mode, int remaining) {
        restTemplate.postForEntity(baseUrl + "/mock/endpoints/" + endpointId
                + "/failures?mode=" + mode + "&remaining=" + remaining, null, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMockEndpoint(String endpointId) {
        return restTemplate.getForEntity(baseUrl + "/mock/endpoints/" + endpointId, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getDelivery(String subscriptionId, String deliveryId) {
        return restTemplate.getForEntity(baseUrl + "/api/subscriptions/" + subscriptionId
                + "/deliveries/" + deliveryId, Map.class).getBody();
    }

    private void waitUntilStatus(String subscriptionId, String deliveryId, String expected, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            tick();
            Map<String, Object> delivery = getDelivery(subscriptionId, deliveryId);
            if (expected.equals(delivery.get("status"))) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Delivery did not reach " + expected + ": "
                + getDelivery(subscriptionId, deliveryId));
    }
}
