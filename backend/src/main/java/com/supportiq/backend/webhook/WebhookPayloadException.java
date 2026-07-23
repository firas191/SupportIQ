package com.supportiq.backend.webhook;

/** Corps du webhook illisible ou champ requis manquant -> 400 (mappé en ProblemDetail). */
public class WebhookPayloadException extends RuntimeException {

    public WebhookPayloadException(String message) {
        super(message);
    }
}
