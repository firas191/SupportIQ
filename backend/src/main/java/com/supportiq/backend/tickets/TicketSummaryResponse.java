package com.supportiq.backend.tickets;

import java.time.Instant;

/**
 * Vue liste d'un ticket (S2-J4). Le corps est tronqué en extrait pour ne pas transferer des
 * messages entiers dans une table paginée ; le detail complet viendra via GET /api/tickets/{id} (S4).
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
        Instant createdAt) {

    private static final int EXCERPT_MAX = 160;

    public static TicketSummaryResponse from(Ticket t) {
        return new TicketSummaryResponse(
                t.getId(),
                t.getExternalRef(),
                t.getSource(),
                t.getCustomerEmail(),
                t.getSubject(),
                excerpt(t.getBody()),
                t.getLanguage(),
                t.getStatus(),
                t.getSlaDueAt(),
                t.getCreatedAt());
    }

    private static String excerpt(String body) {
        if (body == null) {
            return null;
        }
        String flat = body.strip().replaceAll("\\s+", " ");
        return flat.length() <= EXCERPT_MAX ? flat : flat.substring(0, EXCERPT_MAX) + "…";
    }
}
