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
        // Filtre « a risque » (S7-J3). Booleen et non seuil libre : le seuil est une decision
        // d'exploitation, pas un reglage par utilisateur — sinon deux responsables regardant « la
        // file a risque » ne parleraient pas de la meme file.
        Boolean atRisk,
        int page,
        int size,
        String sort,
        String direction) {
}
