package com.github.guoyyit.webpushservicejava.domain;

public enum DeliveryStatus {
    PENDING,
    RETRY_WAIT,
    SENT,
    EXPIRED,
    COALESCED,
    FAILED_PERMANENT,
    FAILED_RETRIES_EXHAUSTED
}
