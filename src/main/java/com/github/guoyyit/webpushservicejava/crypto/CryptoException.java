package com.github.guoyyit.webpushservicejava.crypto;

import com.github.guoyyit.webpushservicejava.domain.ErrorCode;

public class CryptoException extends RuntimeException {
    private final ErrorCode errorCode;

    public CryptoException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CryptoException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
