package com.supportiq.backend.drafts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lecture et revue des brouillons (S5-J4).
 *
 * <p>JdbcTemplate et non JPA, comme {@code analyses}, {@code annotations} et {@code kb_documents} :
 * la table est <b>ecrite par FastAPI</b> et lue ici. Lui donner une entite JPA obligerait a
 * maintenir un mapping pour un cycle de vie qui ne nous appartient pas, et {@code ddl-auto=validate}
 * n'a rien a valider ici.
 *
 * <p>Le jsonb {@code citations} est deserialise par Jackson plutot que mappe par Hibernate : ce
 * n'est jamais un critere de requete, seulement une charge utile transportee vers l'interface.
 */
@Repository
public class DraftRepository {

    private static final Logger log = LoggerFactory.getLogger(DraftRepository.class);

    /** Longueur au-dela de laquelle un passage est tronque avant affichage. */
    private static final int MAX_PASSAGE = 1200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DraftRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Dernier brouillon exploitable d'un ticket.
     *
     * <p>Les rejetes sont exclus : ils restent en base pour la mesure du S5-J5, mais reafficher un
     * brouillon qu'un agent vient d'ecarter serait absurde. L'index {@code (ticket_id, created_at
     * DESC)} de V9 couvre ce tri.
     */
    public Optional<DraftView> findLatest(long ticketId) {
        List<DraftView> rows = jdbc.query(
                """
                SELECT d.id, d.ticket_id, d.content, d.final_content, d.citations::text AS citations,
                       d.status, d.tone, d.low_confidence, d.abstained, d.issues, d.attempts,
                       d.created_at, d.reviewed_at, u.email AS reviewer
                FROM draft_responses d
                LEFT JOIN users u ON u.id = d.reviewed_by
                WHERE d.ticket_id = ? AND d.status <> 'REJECTED'
                ORDER BY d.created_at DESC
                LIMIT 1
                """,
                this::mapDraft,
                ticketId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<DraftView> findById(long draftId) {
        List<DraftView> rows = jdbc.query(
                """
                SELECT d.id, d.ticket_id, d.content, d.final_content, d.citations::text AS citations,
                       d.status, d.tone, d.low_confidence, d.abstained, d.issues, d.attempts,
                       d.created_at, d.reviewed_at, u.email AS reviewer
                FROM draft_responses d
                LEFT JOIN users u ON u.id = d.reviewed_by
                WHERE d.id = ?
                """,
                this::mapDraft,
                draftId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Enregistre la decision humaine.
     *
     * <p>{@code final_content} n'est ecrit que si un texte est fourni : valider sans modifier laisse
     * la colonne nulle, ce qui distingue « approuve tel quel » de « approuve apres reecriture » —
     * la mesure la plus parlante du S5-J5.
     */
    public void review(long draftId, DraftStatus status, String finalContent, long reviewerId) {
        jdbc.update(
                """
                UPDATE draft_responses
                SET status = ?,
                    final_content = COALESCE(?, final_content),
                    reviewed_by = ?,
                    reviewed_at = now()
                WHERE id = ?
                """,
                status.name(),
                finalContent,
                reviewerId,
                draftId);
    }

    /* --- Mapping ---------------------------------------------------------- */

    private DraftView mapDraft(ResultSet rs, int rowNum) throws SQLException {
        return new DraftView(
                rs.getLong("id"),
                rs.getLong("ticket_id"),
                rs.getString("content"),
                rs.getString("final_content"),
                hydrate(parseCitations(rs.getString("citations"))),
                DraftStatus.valueOf(rs.getString("status")),
                rs.getString("tone"),
                rs.getBoolean("low_confidence"),
                rs.getBoolean("abstained"),
                toList(rs.getArray("issues")),
                rs.getInt("attempts"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("reviewed_at")),
                rs.getString("reviewer"));
    }

    /** Citations telles que FastAPI les a ecrites (cles en snake_case). */
    private List<RawCitation> parseCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = mapper.readTree(json);
            List<RawCitation> out = new ArrayList<>();
            for (JsonNode node : array) {
                out.add(new RawCitation(
                        node.path("marker").asInt(),
                        node.hasNonNull("chunk_id") ? node.get("chunk_id").asLong() : null,
                        node.path("source").asText(null),
                        node.path("heading").asText(null),
                        node.path("excerpt").asText("")));
            }
            return out;
        } catch (Exception e) {
            // Un brouillon dont les citations sont illisibles reste affichable : on prefere un
            // panneau sans sources a un ecran en erreur.
            log.warn("Citations illisibles: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Remplace l'extrait tronque par le passage complet, quand il existe encore.
     *
     * <p>Les identifiants de fragment changent a chaque re-import d'un document (remplacement
     * transactionnel, S5-J1) : un brouillon un peu ancien peut donc citer un fragment disparu. On
     * le signale ({@code stale}) au lieu de faire semblant — l'agent doit savoir qu'il verifie sur
     * une copie et non sur la source vivante.
     */
    private List<DraftView.Citation> hydrate(List<RawCitation> raw) {
        if (raw.isEmpty()) {
            return List.of();
        }
        List<Long> ids = raw.stream().map(RawCitation::chunkId).filter(Objects::nonNull).toList();
        Map<Long, String> live = ids.isEmpty() ? Map.of() : loadChunks(ids);

        List<DraftView.Citation> out = new ArrayList<>();
        for (RawCitation c : raw) {
            String fresh = c.chunkId() == null ? null : live.get(c.chunkId());
            out.add(new DraftView.Citation(
                    c.marker(),
                    c.chunkId(),
                    c.source(),
                    c.heading(),
                    truncate(fresh != null ? fresh : c.excerpt()),
                    fresh == null));
        }
        return out;
    }

    /**
     * Un seul aller-retour pour tous les fragments cites.
     *
     * <p>Les identifiants sont passes en <b>un seul parametre</b> puis reconstitues en tableau par
     * PostgreSQL, plutot que dans un {@code IN (?,?,?)} dont le nombre de points d'interrogation
     * varierait a chaque appel — ce qui empeche la reutilisation du plan prepare. Les valeurs
     * restent typees {@code Long} de bout en bout : la concatenation ne peut porter que des
     * chiffres.
     */
    private Map<Long, String> loadChunks(List<Long> ids) {
        String csv = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<Long, String> map = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, content FROM kb_documents WHERE id = ANY (string_to_array(?, ',')::bigint[])",
                csv)) {
            map.put(((Number) row.get("id")).longValue(), (String) row.get("content"));
        }
        return map;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_PASSAGE ? text : text.substring(0, MAX_PASSAGE) + "…";
    }

    private static List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private record RawCitation(int marker, Long chunkId, String source, String heading, String excerpt) {
    }
}
