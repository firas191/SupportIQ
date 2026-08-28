package com.supportiq.backend.sla;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Ecriture de l'echeance SLA (S7-J3).
 *
 * <p>La table {@code sla_risks}, elle, est ecrite par le service IA et lue par la requete de liste
 * (jointure deja presente pour les filtres d'analyse depuis le S4-J3) : elle n'apparait pas ici.
 */
@Repository
public class SlaRepository {

    private final JdbcTemplate jdbc;

    public SlaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Recalcule l'echeance d'un ticket a partir de sa priorite fraichement detectee.
     *
     * <p>Le {@code WHERE resolved_at IS NULL} n'est pas une precaution de style : une analyse peut
     * arriver apres la resolution (ré-analyse manuelle, rattrapage de file), et deplacer alors
     * l'echeance reecrirait retroactivement la verite terrain d'un depassement deja constate. Un
     * historique qui se corrige tout seul ne se mesure plus.
     */
    public int applyDueDate(long ticketId, String priority) {
        return jdbc.update("""
                UPDATE tickets
                SET sla_due_at = created_at + ?::interval
                WHERE id = ? AND resolved_at IS NULL
                """, SlaPolicy.budget(priority).toHours() + " hours", ticketId);
    }

    /** Horodate la resolution. Sans elle, {@code sla_due_at} n'a aucune verite terrain en face. */
    public int markResolved(long ticketId, Instant when) {
        return jdbc.update(
                "UPDATE tickets SET resolved_at = ? WHERE id = ? AND resolved_at IS NULL",
                Timestamp.from(when), ticketId);
    }
}
