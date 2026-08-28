package com.supportiq.backend.alerts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistance des alertes (S7-J2). JdbcTemplate, comme les autres agregats du projet.
 */
@Repository
public class AlertRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AlertRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private static final String SELECT = """
            SELECT a.id, a.type, a.severity, a.scope, a.bucket_start, a.payload,
                   a.acknowledged_by, u.email AS acknowledged_email,
                   a.acknowledged_at, a.created_at
            FROM alerts a
            LEFT JOIN users u ON u.id = a.acknowledged_by
            """;

    /**
     * Alertes recentes, non acquittees d'abord.
     *
     * <p>Le tri n'est pas chronologique pur : ce qui n'a pas ete traite passe devant, meme si c'est
     * plus ancien. Une alerte acquittee est une alerte dont quelqu'un s'occupe ; la laisser en tete
     * parce qu'elle vient d'etre creee ferait descendre celle que personne n'a vue.
     */
    public List<Alert> recent(int limit) {
        return jdbc.query(SELECT + """
                ORDER BY (a.acknowledged_at IS NOT NULL), a.created_at DESC
                LIMIT ?
                """, this::map, limit);
    }

    public Optional<Alert> byId(long id) {
        return jdbc.query(SELECT + "WHERE a.id = ?", this::map, id).stream().findFirst();
    }

    /**
     * Cree l'alerte, ou <b>renvoie vide</b> si la meme existe deja.
     *
     * <p>Meme mecanisme qu'au digest (V12) : la contrainte {@code UNIQUE(type, scope, bucket_start)}
     * arbitre, et la {@link DuplicateKeyException} est traitee comme un <b>resultat normal</b>. Ce
     * n'est pas de la tolerance aux erreurs, c'est la definition de l'idempotence : le detecteur
     * tourne toutes les heures et redecouvre necessairement les pics recents. Sans cette contrainte,
     * un pic du matin aurait produit une alerte par passage jusqu'a sortir de la fenetre.
     */
    public Optional<Long> insertIfAbsent(String type, String severity, String scope,
            Instant bucketStart, String payloadJson) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    INSERT INTO alerts (type, severity, scope, bucket_start, payload)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    RETURNING id
                    """, Long.class, type, severity, scope, Timestamp.from(bucketStart), payloadJson));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    /**
     * Acquitte l'alerte si elle ne l'est pas deja.
     *
     * <p>La condition {@code acknowledged_at IS NULL} est dans le SQL et non dans un test prealable :
     * deux responsables qui cliquent en meme temps ne doivent pas se voler l'attribution. Le premier
     * ecrit, le second ne modifie rien — et voit qui s'en est charge.
     */
    public int acknowledge(long id, long userId) {
        return jdbc.update("""
                UPDATE alerts SET acknowledged_by = ?, acknowledged_at = now()
                WHERE id = ? AND acknowledged_at IS NULL
                """, userId, id);
    }

    public int countOpen() {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT COUNT(*) FROM alerts WHERE acknowledged_at IS NULL", Integer.class))
                .orElse(0);
    }

    private Alert map(ResultSet rs, int rowNum) throws SQLException {
        return new Alert(
                rs.getLong("id"),
                rs.getString("type"),
                rs.getString("severity"),
                rs.getString("scope"),
                toInstant(rs.getTimestamp("bucket_start")),
                readJson(rs.getString("payload")),
                (Long) rs.getObject("acknowledged_by"),
                rs.getString("acknowledged_email"),
                toInstant(rs.getTimestamp("acknowledged_at")),
                toInstant(rs.getTimestamp("created_at")));
    }

    private JsonNode readJson(String raw) {
        try {
            return raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
        } catch (Exception e) {
            // Un payload illisible ne doit pas faire disparaitre l'alerte de la liste : le fait
            // qu'un pic ait ete detecte compte plus que le detail chiffre qui l'accompagne.
            return mapper.createObjectNode();
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
