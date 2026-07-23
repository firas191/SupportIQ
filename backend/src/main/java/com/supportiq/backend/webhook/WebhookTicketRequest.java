package com.supportiq.backend.webhook;

/**
 * Charge utile JSON du webhook d'ingestion (S2-J4). Desérialisée manuellement depuis le corps brut
 * (le HMAC porte sur les octets bruts), donc la validation est faite dans le service, pas par annotations.
 * {@code source} n'est pas accepté du client : tout ticket entré par ce canal est forcé à WEBHOOK.
 */
public record WebhookTicketRequest(
        String externalRef,
        String customerEmail,
        String subject,
        String body,
        String language) {
}
