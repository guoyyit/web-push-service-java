package com.github.guoyyit.webpushservicejava.push.crypto;

public class DecryptException extends Exception {
    private final DecryptFailureReason reason;

    public DecryptException(DecryptFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DecryptException(DecryptFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public DecryptFailureReason getReason() {
        return reason;
    }
}
