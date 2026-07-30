package com.supportiq.backend.realtime;

/**
 * Message pousse aux clients WebSocket (S4-J5). Volontairement **minimal** : le client s'en sert
 * comme d'un signal (« un ticket est arrive / a ete analyse ») et recharge les donnees via l'API REST
 * s'il en a besoin. On evite ainsi de dupliquer les regles de securite/RBAC dans le canal temps reel.
 */
public record RealtimeEvent(
        String type,          // TICKET_CREATED | TICKET_ANALYZED
        Long ticketId,
        String externalRef,
        String subject,
        String category,      // renseigne pour TICKET_ANALYZED
        String priority,
        String sentiment) {

    public static RealtimeEvent created(Long ticketId, String externalRef, String subject) {
        return new RealtimeEvent("TICKET_CREATED", ticketId, externalRef, subject, null, null, null);
    }

    public static RealtimeEvent analyzed(Long ticketId, String externalRef, String category,
            String priority, String sentiment) {
        return new RealtimeEvent("TICKET_ANALYZED", ticketId, externalRef, null,
                category, priority, sentiment);
    }
}
