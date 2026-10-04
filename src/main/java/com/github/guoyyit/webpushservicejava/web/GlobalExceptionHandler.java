package com.github.guoyyit.webpushservicejava.web;

import com.github.guoyyit.webpushservicejava.domain.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Map<String, Object>> handleServiceException(ServiceException exception) {
        return ResponseEntity.status(httpStatus(exception.getErrorCode()))
                .body(body(exception.getErrorCode(), exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(body(ErrorCode.INVALID_ARGUMENT, exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(body(ErrorCode.INVALID_ARGUMENT, "Malformed JSON request body"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(ErrorCode.INVALID_ARGUMENT, exception.getMessage()));
    }

    private HttpStatus httpStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case SUBSCRIPTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VAPID_MISSING -> HttpStatus.UNAUTHORIZED;
            case VAPID_BAD_SIGNATURE, VAPID_EXPIRED, VAPID_AUDIENCE_MISMATCH -> HttpStatus.FORBIDDEN;
            case INVALID_ENDPOINT, INVALID_PUBLIC_KEY, INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case DECRYPT_KEY_MISMATCH, DECRYPT_TAMPERED, PAYLOAD_EXPIRED -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    private Map<String, Object> body(ErrorCode errorCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", errorCode.name());
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
