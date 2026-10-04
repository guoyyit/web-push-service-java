package com.github.guoyyit.webpushservicejava.push.web;

import com.github.guoyyit.webpushservicejava.push.exception.ErrorCode;
import com.github.guoyyit.webpushservicejava.push.exception.PushException;
import com.github.guoyyit.webpushservicejava.push.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PushException.class)
    public ResponseEntity<ErrorResponse> handlePushException(PushException exception) {
        return ResponseEntity.status(statusFor(exception.getCode()))
                .body(new ErrorResponse(exception.getCode().name(), exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST.name(), exception.getMessage()));
    }

    private static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case INVALID_ENDPOINT, INVALID_KEY, INVALID_REQUEST, VAPID_MALFORMED -> HttpStatus.BAD_REQUEST;
            case VAPID_MISSING -> HttpStatus.UNAUTHORIZED;
            case VAPID_SIGNATURE_INVALID, VAPID_EXPIRED, VAPID_AUDIENCE_MISMATCH -> HttpStatus.FORBIDDEN;
            case SUBSCRIPTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case MESSAGE_EXPIRED -> HttpStatus.GONE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
