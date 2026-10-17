package com.drawback.security;

import org.springframework.http.HttpStatus;

/** Business-rule failure carrying the HTTP status it should map to. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException notFound(String what) { return new ApiException(HttpStatus.NOT_FOUND, what + " not found"); }
    public static ApiException conflict(String msg) { return new ApiException(HttpStatus.CONFLICT, msg); }
    public static ApiException forbidden(String msg) { return new ApiException(HttpStatus.FORBIDDEN, msg); }
    public static ApiException badRequest(String msg) { return new ApiException(HttpStatus.BAD_REQUEST, msg); }
    public static ApiException unauthorized(String msg) { return new ApiException(HttpStatus.UNAUTHORIZED, msg); }
}
