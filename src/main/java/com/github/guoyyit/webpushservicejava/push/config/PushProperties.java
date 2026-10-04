package com.github.guoyyit.webpushservicejava.push.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "push")
public class PushProperties {
    private String dataDir = "./push-data";
    private String vapidAudience = "http://localhost:8080";
    private String vapidSubject = "mailto:admin@localhost";
    private long vapidExpirySeconds = 43200;
    private int maxDeliveryAttempts = 5;
    private long retryBaseDelayMillis = 200;
    private double retryBackoffMultiplier = 2.0;
    private long maxRetryDelayMillis = 30000;
    private long schedulerIntervalMillis = 100;
    private long defaultTtlSeconds = 3600;
    private long maxTtlSeconds = 86400;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getVapidAudience() {
        return vapidAudience;
    }

    public void setVapidAudience(String vapidAudience) {
        this.vapidAudience = vapidAudience;
    }

    public String getVapidSubject() {
        return vapidSubject;
    }

    public void setVapidSubject(String vapidSubject) {
        this.vapidSubject = vapidSubject;
    }

    public long getVapidExpirySeconds() {
        return vapidExpirySeconds;
    }

    public void setVapidExpirySeconds(long vapidExpirySeconds) {
        this.vapidExpirySeconds = vapidExpirySeconds;
    }

    public int getMaxDeliveryAttempts() {
        return maxDeliveryAttempts;
    }

    public void setMaxDeliveryAttempts(int maxDeliveryAttempts) {
        this.maxDeliveryAttempts = maxDeliveryAttempts;
    }

    public long getRetryBaseDelayMillis() {
        return retryBaseDelayMillis;
    }

    public void setRetryBaseDelayMillis(long retryBaseDelayMillis) {
        this.retryBaseDelayMillis = retryBaseDelayMillis;
    }

    public double getRetryBackoffMultiplier() {
        return retryBackoffMultiplier;
    }

    public void setRetryBackoffMultiplier(double retryBackoffMultiplier) {
        this.retryBackoffMultiplier = retryBackoffMultiplier;
    }

    public long getMaxRetryDelayMillis() {
        return maxRetryDelayMillis;
    }

    public void setMaxRetryDelayMillis(long maxRetryDelayMillis) {
        this.maxRetryDelayMillis = maxRetryDelayMillis;
    }

    public long getSchedulerIntervalMillis() {
        return schedulerIntervalMillis;
    }

    public void setSchedulerIntervalMillis(long schedulerIntervalMillis) {
        this.schedulerIntervalMillis = schedulerIntervalMillis;
    }

    public long getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public void setDefaultTtlSeconds(long defaultTtlSeconds) {
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public long getMaxTtlSeconds() {
        return maxTtlSeconds;
    }

    public void setMaxTtlSeconds(long maxTtlSeconds) {
        this.maxTtlSeconds = maxTtlSeconds;
    }
}
