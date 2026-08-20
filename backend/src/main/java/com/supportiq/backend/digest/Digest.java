package com.supportiq.backend.digest;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Une synthese hebdomadaire telle qu'elle est stockee et affichee (S6-J4).
 *
 * <p>{@code sentAt} et {@code sendError} sont separes de la generation : un digest peut exister
 * sans etre parti (serveur SMTP injoignable). Les confondre ferait perdre le travail de generation
 * a chaque echec d'envoi, et surtout rendrait l'echec <b>invisible</b> — personne ne saurait que le
 * digest de la semaine n'est jamais arrive.
 */
public record Digest(
        Long id,
        LocalDate weekStart,
        String markdown,
        Instant generatedAt,
        Instant sentAt,
        String recipients,
        String sendError) {

    public boolean sent() {
        return sentAt != null;
    }
}
