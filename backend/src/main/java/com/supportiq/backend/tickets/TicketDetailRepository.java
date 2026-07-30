package com.supportiq.backend.tickets;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lecture de la fiche ticket et ecritures de la boucle human-in-the-loop (S4-J4).
 *
 * <p>JdbcTemplate, comme le dashboard et la recherche : la table `analyses` est ecrite par FastAPI et
 * n'a pas d'entite JPA (ecart assume S3-J3), et on a besoin d'une jointure ticket+analyse en une
 * requete plutot que d'un lazy-loading.
 */
@Repository
public class TicketDetailRepository {

    private final JdbcTemplate jdbc;

    public TicketDetailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ticket + son analyse (LEFT JOIN : un ticket non analyse reste consultable). */
    public Optional<TicketDetailResponse> findDetail(long id) {
        List<TicketDetailResponse> rows = jdbc.query("""
                SELECT t.id, t.external_ref, t.source, t.customer_email, t.subject, t.body,
                       t.language, t.status, t.sla_due_at, t.created_at, t.merged_into_id,
                       a.priority, a.category, a.sentiment, a.keywords, a.confidence,
                       a.model_used, a.escalated_to_llm, a.created_at AS analysis_created_at
                FROM tickets t
                LEFT JOIN analyses a ON a.ticket_id = t.id
                WHERE t.id = ?
                """, (rs, rowNum) -> map(rs), id);
        return rows.stream().findFirst();
    }

    /** Valeur actuellement predite pour un champ (sert de `predicted` dans l'annotation). */
    public Optional<String> currentValue(long ticketId, String field) {
        // `field` est whiteliste par le service (jamais une entree utilisateur brute).
        List<String> values = jdbc.queryForList(
                "SELECT " + field + " FROM analyses WHERE ticket_id = ?", String.class, ticketId);
        return values.stream().findFirst();
    }

    /** Trace la correction humaine (historique conserve, jamais ecrase). */
    public void insertAnnotation(long ticketId, String field, String predicted, String corrected,
            long correctedBy) {
        jdbc.update("""
                INSERT INTO annotations (ticket_id, field, predicted, corrected, corrected_by)
                VALUES (?, ?, ?, ?, ?)
                """, ticketId, field, predicted, corrected, correctedBy);
    }

    /** Applique la correction a l'analyse courante (ce que voit l'utilisateur). */
    public int applyCorrection(long ticketId, String field, String corrected) {
        return jdbc.update("UPDATE analyses SET " + field + " = ? WHERE ticket_id = ?",
                corrected, ticketId);
    }

    /** Nombre de corrections deja enregistrees pour un ticket (affiche dans la fiche). */
    public int countAnnotations(long ticketId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM annotations WHERE ticket_id = ?", Integer.class, ticketId);
        return count == null ? 0 : count;
    }

    /** Fusion : le doublon pointe vers le ticket maitre et passe en MERGED. */
    public int merge(long duplicateId, long targetId) {
        return jdbc.update(
                "UPDATE tickets SET merged_into_id = ?, status = 'MERGED' WHERE id = ?",
                targetId, duplicateId);
    }

    public boolean exists(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE id = ?",
                Integer.class, id);
        return count != null && count > 0;
    }

    private static TicketDetailResponse map(ResultSet rs) throws SQLException {
        String priority = rs.getString("priority");
        // `confidence` est un NUMERIC(4,3) : le driver renvoie un BigDecimal, pas un Double.
        BigDecimal confidence = rs.getBigDecimal("confidence");
        TicketDetailResponse.Analysis analysis = priority == null ? null
                : new TicketDetailResponse.Analysis(
                        priority,
                        rs.getString("category"),
                        rs.getString("sentiment"),
                        toList(rs.getArray("keywords")),
                        confidence == null ? null : confidence.doubleValue(),
                        rs.getString("model_used"),
                        rs.getBoolean("escalated_to_llm"),
                        toInstant(rs.getTimestamp("analysis_created_at")));

        Long mergedInto = rs.getObject("merged_into_id", Long.class);
        return new TicketDetailResponse(
                rs.getLong("id"),
                rs.getString("external_ref"),
                TicketSource.valueOf(rs.getString("source")),
                rs.getString("customer_email"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getString("language"),
                TicketStatus.valueOf(rs.getString("status")),
                toInstant(rs.getTimestamp("sla_due_at")),
                toInstant(rs.getTimestamp("created_at")),
                mergedInto,
                analysis,
                List.of());   // similaires ajoutes par le service (appel au service IA)
    }

    private static List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        String[] values = (String[]) array.getArray();
        return values == null ? List.of() : List.of(values);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
