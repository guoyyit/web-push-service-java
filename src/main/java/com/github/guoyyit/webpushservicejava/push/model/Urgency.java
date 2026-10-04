package com.github.guoyyit.webpushservicejava.push.model;

public enum Urgency {
    VERY_LOW,
    LOW,
    NORMAL,
    HIGH;

    public static Urgency fromString(String value) {
        if (value == null) {
            return NORMAL;
        }
        return switch (value.trim().toLowerCase()) {
            case "very-low" -> VERY_LOW;
            case "low" -> LOW;
            case "high" -> HIGH;
            case "normal" -> NORMAL;
            default -> throw new IllegalArgumentException("unknown urgency: " + value);
        };
    }

    public String headerValue() {
        return switch (this) {
            case VERY_LOW -> "very-low";
            case LOW -> "low";
            case NORMAL -> "normal";
            case HIGH -> "high";
        };
    }
}
