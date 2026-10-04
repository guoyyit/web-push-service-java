package com.github.guoyyit.webpushservicejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.github.guoyyit.webpushservicejava.Application;
import com.github.guoyyit.webpushservicejava.delivery.DeliveryScheduler;
import com.github.guoyyit.webpushservicejava.vapid.VapidService;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RestartRecoveryIntegrationTest {

    private RestTemplate restTemplate = new RestTemplate();

    @Test
    void subscriptionsAndPendingDeliveriesSurviveRestart() throws Exception {
        String dataDir = "target/test-data/restart-" + System.nanoTime();
        int port = 8911;

        ConfigurableApplicationContext first = startContext(port, dataDir, "100000");
        try {
            String baseUrl = "http://localhost:" + port;

            Map<String, Object> endpoint = post(baseUrl + "/mock/endpoints", null);
            String endpointId = (String) endpoint.get("endpointId");
            String rawEndpointUrl = String.valueOf(endpoint.get("endpointUrl"));
            String endpointUrl = rawEndpointUrl.startsWith("http") ? rawEndpointUrl
                    : baseUrl + rawEndpointUrl;

            Map<String, Object> subscriptionBody = new HashMap<>();
            subscriptionBody.put("endpoint", endpointUrl);
            subscriptionBody.put("p256dh", endpoint.get("p256dh"));
            subscriptionBody.put("auth", endpoint.get("auth"));
            Map<String, Object> subscription = post(baseUrl + "/api/subscriptions", subscriptionBody);
            String subscriptionId = (String) subscription.get("id");

            VapidService firstVapid = first.getBean(VapidService.class);
            String publicKeyBefore = firstVapid.getPublicKeyBase64();
            String tokenBefore = firstVapid.signAuthorization(baseUrl, "mailto:r@localhost");

            Map<String, Object> pushBody = new HashMap<>();
            pushBody.put("payload", "survive-restart");
            pushBody.put("ttlSeconds", 3600);
            HttpHeaders pushHeaders = new HttpHeaders();
            pushHeaders.set("Authorization",
                    firstVapid.signAuthorization(baseUrl, "mailto:r@localhost"));
            ResponseEntity<Map> pushResponse = restTemplate.postForEntity(
                    baseUrl + "/api/subscriptions/" + subscriptionId + "/push",
                    new HttpEntity<>(pushBody, pushHeaders), Map.class);
            assertEquals(HttpStatus.OK, pushResponse.getStatusCode());
            String deliveryId = (String) pushResponse.getBody().get("deliveryId");

            first.close();

            ConfigurableApplicationContext second = startContext(port, dataDir, "100000");
            try {
                VapidService secondVapid = second.getBean(VapidService.class);
                assertEquals(publicKeyBefore, secondVapid.getPublicKeyBase64(),
                        "VAPID public key must be stable across restart");
                assertNotNull(secondVapid.verify(tokenBefore, baseUrl),
                        "old JWT must still validate after restart");

                ResponseEntity<Map> restoredSubscription = restTemplate.getForEntity(
                        baseUrl + "/api/subscriptions/" + subscriptionId, Map.class);
                assertEquals(HttpStatus.OK, restoredSubscription.getStatusCode());

                ResponseEntity<Map> restoredDelivery = restTemplate.getForEntity(
                        baseUrl + "/api/subscriptions/" + subscriptionId
                                + "/deliveries/" + deliveryId, Map.class);
                assertEquals(HttpStatus.OK, restoredDelivery.getStatusCode());
                assertEquals("PENDING", restoredDelivery.getBody().get("status"));

                second.getBean(DeliveryScheduler.class).tick();

                ResponseEntity<Map> afterDelivery = restTemplate.getForEntity(
                        baseUrl + "/api/subscriptions/" + subscriptionId
                                + "/deliveries/" + deliveryId, Map.class);
                assertEquals("SENT", afterDelivery.getBody().get("status"));
            } finally {
                second.close();
            }
        } finally {
            if (first.isActive()) {
                first.close();
            }
        }
    }

    private ConfigurableApplicationContext startContext(int port, String dataDir, String interval) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "webpush.data-dir=" + dataDir,
                        "webpush.dispatch-interval-millis=" + interval,
                        "webpush.retry-backoff-millis=20",
                        "webpush.max-retries=3");
        ConfigurableApplicationContext context = builder.run(
                "--server.port=" + port,
                "--webpush.data-dir=" + dataDir,
                "--webpush.dispatch-interval-millis=" + interval,
                "--webpush.retry-backoff-millis=20",
                "--webpush.max-retries=3");
        waitForPort(port, 10_000);
        return context;
    }

    private void waitForPort(int port, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
                return;
            } catch (Exception e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for port", interrupted);
                }
            }
        }
        throw new IllegalStateException("Server port " + port + " did not become ready");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String url, Object body) {
        return restTemplate.postForEntity(url, body, Map.class).getBody();
    }
}
