package com.github.guoyyit.webpushservicejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.webpushservicejava.push.crypto.CryptoUtils;
import com.github.guoyyit.webpushservicejava.push.crypto.EncryptedPayload;
import com.github.guoyyit.webpushservicejava.push.crypto.PayloadCipher;
import com.github.guoyyit.webpushservicejava.push.crypto.VapidService;
import com.github.guoyyit.webpushservicejava.push.endpoint.EndpointRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    private static final Path DATA_DIR = createDataDir();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EndpointRegistry endpointRegistry;

    @Autowired
    private PayloadCipher payloadCipher;

    private final ObjectMapper mapper = new ObjectMapper();

    private static Path createDataDir() {
        try {
            return Files.createTempDirectory("push-api-test-");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("push.data-dir", DATA_DIR::toString);
        registry.add("push.scheduler-interval-millis", () -> "50");
    }

    @Test
    void fullPushFlowOverRest() throws Exception {
        MvcResult endpointResult = mockMvc.perform(post("/api/mock-endpoints"))
                .andExpect(status().isOk())
                .andReturn();
        String endpoint = mapper.readTree(endpointResult.getResponse().getContentAsString())
                .get("endpoint").asText();

        KeyPair subscriberKeys = VapidService.generateKeyPair();
        String p256dh = CryptoUtils.encodePublicKey(subscriberKeys.getPublic());
        String auth = CryptoUtils.base64UrlEncode("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post("/api/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://not-local.example.com\",\"p256dh\":\"" + p256dh
                                + "\",\"auth\":\"" + auth + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ENDPOINT"));

        mockMvc.perform(post("/api/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"" + endpoint + "\",\"p256dh\":\"bad\",\"auth\":\"" + auth + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_KEY"));

        MvcResult subscriptionResult = mockMvc.perform(post("/api/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"" + endpoint + "\",\"p256dh\":\"" + p256dh
                                + "\",\"auth\":\"" + auth + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();
        String subscriptionId = mapper.readTree(subscriptionResult.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(get("/api/subscriptions/" + subscriptionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpoint").value(endpoint));

        mockMvc.perform(post("/api/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subscriptionId\":\"" + subscriptionId + "\",\"payload\":\"hi\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("VAPID_MISSING"));

        MvcResult tokenResult = mockMvc.perform(post("/api/keys/token"))
                .andExpect(status().isOk())
                .andReturn();
        String token = mapper.readTree(tokenResult.getResponse().getContentAsString()).get("token").asText();

        KeyPair foreignKeys = VapidService.generateKeyPair();
        String foreignToken = new VapidService().sign("http://localhost:8080",
                Instant.now().plusSeconds(600), "mailto:x@y.z", foreignKeys.getPrivate());
        mockMvc.perform(post("/api/push")
                        .header("Authorization", "Bearer " + foreignToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subscriptionId\":\"" + subscriptionId + "\",\"payload\":\"hi\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VAPID_SIGNATURE_INVALID"));

        mockMvc.perform(post("/api/push")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subscriptionId\":\"" + subscriptionId
                                + "\",\"payload\":\"hello e2e\",\"ttlSeconds\":3600,\"urgency\":\"high\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING"));

        List<EndpointRegistry.ReceivedMessage> received = awaitMessages(endpoint, 1);
        EncryptedPayload encrypted = received.get(0).payload();
        byte[] plaintext = payloadCipher.decrypt(encrypted,
                CryptoUtils.encodePrivateKey(subscriberKeys.getPrivate()), p256dh, auth);
        assertEquals("hello e2e", new String(plaintext, StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/keys/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").exists());

        mockMvc.perform(post("/api/keys/rotate"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/push")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subscriptionId\":\"" + subscriptionId + "\",\"payload\":\"after-rotation\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VAPID_SIGNATURE_INVALID"));
    }

    private List<EndpointRegistry.ReceivedMessage> awaitMessages(String endpoint, int count) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            List<EndpointRegistry.ReceivedMessage> received = endpointRegistry.received(endpoint);
            if (received.size() >= count) {
                return received;
            }
            Thread.sleep(50);
        }
        List<EndpointRegistry.ReceivedMessage> received = endpointRegistry.received(endpoint);
        assertTrue(received.size() >= count, "expected " + count + " messages, got " + received.size());
        return received;
    }
}
