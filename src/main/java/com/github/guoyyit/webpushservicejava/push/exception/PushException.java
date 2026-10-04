package com.github.guoyyit.webpushservicejava.push.exception;

public class PushException extends RuntimeException {
    private final ErrorCode code;

    public PushException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public PushException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
