package com.supportiq.backend.webhook;

/** Quota du webhook depasse -> 429 Too Many Requests (mappé en ProblemDetail). */
public class WebhookRateLimitException extends RuntimeException {

    public WebhookRateLimitException(String message) {
        super(message);
    }
}
