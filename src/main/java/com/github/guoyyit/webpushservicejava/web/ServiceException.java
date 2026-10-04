package com.github.guoyyit.webpushservicejava.web;

import com.github.guoyyit.webpushservicejava.domain.ErrorCode;

public class ServiceException extends RuntimeException {
    private final ErrorCode errorCode;

    public ServiceException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ServiceException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
