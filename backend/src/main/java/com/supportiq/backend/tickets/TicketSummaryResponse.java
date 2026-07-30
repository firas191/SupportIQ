package com.supportiq.backend.tickets;

import java.time.Instant;

/**
 * Vue liste d'un ticket (S2-J4). Le corps est tronqué en extrait pour ne pas transferer des
 * messages entiers dans une table paginée ; le detail complet vient de GET /api/tickets/{id}.
 *
 * <p><b>Refonte d'interface :</b> les trois champs d'analyse ({@code priority}, {@code category},
 * {@code sentiment}) ont été ajoutés à cette vue. Ils étaient déjà **filtrables** depuis S4-J3 (la
 * requête joint {@code analyses} pour appliquer les filtres) mais n'étaient pas **retournés** :
 * l'interface pouvait donc filtrer sur une information qu'elle ne pouvait jamais afficher. Comme la
 * jointure existe déjà, le coût est nul — trois colonnes de plus dans le SELECT, aucune requête
 * supplémentaire.
 *
 * <p>Ces champs sont {@code null} tant que le ticket n'a pas été analysé (jointure externe) : c'est
 * une information utile en soi, la liste montre alors « en attente ».
 */
public record TicketSummaryResponse(
        Long id,
        String externalRef,
        TicketSource source,
        String customerEmail,
        String subject,
        String excerpt,
        String language,
        TicketStatus status,
        Instant slaDueAt,
        Instant createdAt,
        String priority,
        String category,
        String sentiment) {
}
