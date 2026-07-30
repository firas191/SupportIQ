package com.supportiq.backend.tickets;

import java.time.Instant;
import java.util.List;

/**
 * Fiche ticket complete (S4-J4, rapport §6 : ticket + analyse + similaires).
 * Le brouillon de reponse (RAG) sera ajoute en Semaine 5.
 */
public record TicketDetailResponse(
        Long id,
        String externalRef,
        TicketSource source,
        String customerEmail,
        String subject,
        String body,
        String language,
        TicketStatus status,
        Instant slaDueAt,
        Instant createdAt,
        Long mergedIntoId,
        Analysis analysis,          // null si le ticket n'a pas encore ete analyse
        List<SimilarTicket> similar) {

    /** Resultat du triage IA affiche dans la fiche (badge de confiance, mots-cles, tracabilite). */
    public record Analysis(
            String priority,
            String category,
            String sentiment,
            List<String> keywords,
            Double confidence,
            String modelUsed,
            boolean escalatedToLlm,
            Instant createdAt) {
    }

    /** Ticket proche (pgvector). `duplicate` = candidat a la fusion (meme categorie, cosinus eleve). */
    public record SimilarTicket(
            Long ticketId,
            String subject,
            String category,
            Double similarity,
            boolean duplicate) {
    }
}
