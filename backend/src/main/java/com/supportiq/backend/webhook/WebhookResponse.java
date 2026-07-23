package com.supportiq.backend.webhook;

/**
 * Réponse du webhook. {@code result} = ACCEPTED (ticket créé + analyse asynchrone déclenchée)
 * ou DUPLICATE (external_ref déjà connu : idempotence, aucun nouveau message publié).
 */
public record WebhookResponse(Long ticketId, String result) {

    static WebhookResponse accepted(Long ticketId) {
        return new WebhookResponse(ticketId, "ACCEPTED");
    }

    static WebhookResponse duplicate(Long ticketId) {
        return new WebhookResponse(ticketId, "DUPLICATE");
    }
}
