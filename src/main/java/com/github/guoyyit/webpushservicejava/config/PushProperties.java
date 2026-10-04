package com.github.guoyyit.webpushservicejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "webpush")
public class PushProperties {

    private String dataDir = "data";
    private int maxRetries = 3;
    private long retryBackoffMillis = 500;
    private double backoffMultiplier = 2.0;
    private long dispatchIntervalMillis = 200;
    private long vapidTtlSeconds = 43200;

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public long getRetryBackoffMillis() { return retryBackoffMillis; }
    public void setRetryBackoffMillis(long retryBackoffMillis) { this.retryBackoffMillis = retryBackoffMillis; }

    public double getBackoffMultiplier() { return backoffMultiplier; }
    public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }

    public long getDispatchIntervalMillis() { return dispatchIntervalMillis; }
    public void setDispatchIntervalMillis(long dispatchIntervalMillis) { this.dispatchIntervalMillis = dispatchIntervalMillis; }

    public long getVapidTtlSeconds() { return vapidTtlSeconds; }
    public void setVapidTtlSeconds(long vapidTtlSeconds) { this.vapidTtlSeconds = vapidTtlSeconds; }
}
