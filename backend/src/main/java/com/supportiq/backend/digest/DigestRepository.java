package com.supportiq.backend.digest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistance des digests (S6-J4). JdbcTemplate, comme les autres agregats du projet.
 */
@Repository
public class DigestRepository {

    private final JdbcTemplate jdbc;

    public DigestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Digest> recent(int limit) {
        return jdbc.query("""
                SELECT id, week_start, markdown, generated_at, sent_at, recipients, send_error
                FROM digests ORDER BY week_start DESC LIMIT ?
                """, this::map, limit);
    }

    public Optional<Digest> byWeek(LocalDate weekStart) {
        return jdbc.query("""
                SELECT id, week_start, markdown, generated_at, sent_at, recipients, send_error
                FROM digests WHERE week_start = ?
                """, this::map, weekStart).stream().findFirst();
    }

    public Optional<Digest> byId(long id) {
        return jdbc.query("""
                SELECT id, week_start, markdown, generated_at, sent_at, recipients, send_error
                FROM digests WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    /**
     * Insere le digest d'une semaine, ou <b>renvoie vide</b> si un autre l'a deja fait.
     *
     * <p>C'est ici que se joue la sûrete multi-instance. Deux noeuds qui declenchent la generation
     * du lundi au meme instant arrivent tous les deux ici ; la contrainte {@code UNIQUE(week_start)}
     * en laisse passer un et l'autre recoit une {@link DuplicateKeyException}. On la traite comme
     * un resultat normal, pas comme une erreur : le digest existe, c'est tout ce qui compte.
     *
     * <p>Verifier « existe-t-il deja ? » avant d'inserer ne suffirait pas — deux noeuds peuvent
     * lire « non » simultanement. Seule la base peut arbitrer.
     */
    public Optional<Long> insertIfAbsent(LocalDate weekStart, String markdown, String statsJson) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    INSERT INTO digests (week_start, markdown, stats)
                    VALUES (?, ?, ?::jsonb)
                    RETURNING id
                    """, Long.class, weekStart, markdown, statsJson));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    /** Remplace le contenu d'un digest existant (regeneration explicite par un responsable). */
    public void replace(long id, String markdown, String statsJson) {
        jdbc.update("""
                UPDATE digests SET markdown = ?, stats = ?::jsonb, generated_at = now(),
                                   sent_at = NULL, send_error = NULL
                WHERE id = ?
                """, markdown, statsJson, id);
    }

    public void markSent(long id, String recipients) {
        jdbc.update("UPDATE digests SET sent_at = now(), recipients = ?, send_error = NULL WHERE id = ?",
                recipients, id);
    }

    /** Trace l'echec sans effacer le digest : il reste consultable et renvoyable. */
    public void markFailed(long id, String error) {
        jdbc.update("UPDATE digests SET send_error = ? WHERE id = ?",
                error == null ? "erreur inconnue" : error.substring(0, Math.min(error.length(), 400)), id);
    }

    private Digest map(ResultSet rs, int rowNum) throws SQLException {
        return new Digest(
                rs.getLong("id"),
                rs.getObject("week_start", LocalDate.class),
                rs.getString("markdown"),
                toInstant(rs.getTimestamp("generated_at")),
                toInstant(rs.getTimestamp("sent_at")),
                rs.getString("recipients"),
                rs.getString("send_error"));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
