package com.github.guoyyit.webpushservicejava.tests;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    public void advanceSeconds(long seconds) {
        instant = instant.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
