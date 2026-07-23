package com.supportiq.backend.webhook;

/** Clé API absente/incorrecte ou signature HMAC invalide -> 401 (mappé en ProblemDetail). */
public class WebhookAuthException extends RuntimeException {

    public WebhookAuthException(String message) {
        super(message);
    }
}
