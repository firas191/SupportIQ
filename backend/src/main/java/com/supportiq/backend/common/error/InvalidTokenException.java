package com.supportiq.backend.common.error;

/** Refresh token inconnu, expire ou revoque -> 401 Unauthorized. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
