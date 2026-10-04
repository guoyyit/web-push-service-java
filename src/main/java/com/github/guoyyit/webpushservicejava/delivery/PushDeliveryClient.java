package com.github.guoyyit.webpushservicejava.delivery;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class PushDeliveryClient {

    public enum ResultType {
        SUCCESS,
        TEMPORARY_FAILURE,
        PERMANENT_FAILURE
    }

    public static class DeliveryResult {
        private final ResultType type;
        private final String reason;
        private final int httpStatus;

        public DeliveryResult(ResultType type, String reason, int httpStatus) {
            this.type = type;
            this.reason = reason;
            this.httpStatus = httpStatus;
        }

        public ResultType getType() { return type; }
        public String getReason() { return reason; }
        public int getHttpStatus() { return httpStatus; }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public DeliveryResult deliver(String endpoint, String encryptedBodyJson, String authorizationHeader,
                                  Integer ttlSeconds, String urgency, String topic) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/octet-stream")
                    .header("Content-Encoding", "aes128gcm")
                    .header("TTL", String.valueOf(ttlSeconds == null ? 0 : ttlSeconds))
                    .header("Urgency", urgency == null ? "normal" : urgency.toLowerCase());
            if (authorizationHeader != null && !authorizationHeader.isBlank()) {
                builder.header("Authorization", authorizationHeader);
            }
            if (topic != null && !topic.isBlank()) {
                builder.header("Topic", topic);
            }
            builder.POST(HttpRequest.BodyPublishers.ofString(encryptedBodyJson));

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new DeliveryResult(ResultType.SUCCESS, "delivered", status);
            }
            if (status == 404 || status == 410) {
                return new DeliveryResult(ResultType.PERMANENT_FAILURE,
                        "endpoint permanently invalid (HTTP " + status + ")", status);
            }
            String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            String reason = "temporary failure (HTTP " + status + ")"
                    + (retryAfter == null ? "" : ", retry-after=" + retryAfter);
            return new DeliveryResult(ResultType.TEMPORARY_FAILURE, reason, status);
        } catch (java.net.http.HttpTimeoutException e) {
            return new DeliveryResult(ResultType.TEMPORARY_FAILURE, "request timed out", 0);
        } catch (java.io.IOException e) {
            return new DeliveryResult(ResultType.TEMPORARY_FAILURE, "network error: " + e.getMessage(), 0);
        } catch (IllegalArgumentException e) {
            return new DeliveryResult(ResultType.PERMANENT_FAILURE, "invalid endpoint URI: " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeliveryResult(ResultType.TEMPORARY_FAILURE, "interrupted", 0);
        }
    }
}
