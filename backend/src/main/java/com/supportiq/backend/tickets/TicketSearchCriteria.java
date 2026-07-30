package com.supportiq.backend.tickets;

/**
 * Criteres de recherche de la liste de tickets (S4-J3). `q` declenche la recherche full-text ;
 * les autres champs sont des filtres structures combinables. Tous nullables = ignores.
 */
public record TicketSearchCriteria(
        String q,
        TicketStatus status,
        TicketSource source,
        String language,
        String category,     // filtres issus de la table analyses (S3)
        String priority,
        String sentiment,
        int page,
        int size,
        String sort,
        String direction) {
}
