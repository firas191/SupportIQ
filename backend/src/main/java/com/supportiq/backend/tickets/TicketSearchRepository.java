package com.supportiq.backend.tickets;

import com.supportiq.backend.common.PageResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Recherche de tickets : full-text PostgreSQL + filtres structures (S4-J3).
 *
 * <p>Pourquoi du **SQL natif** ici plutot que les JPA Specifications du J4-S2 : la recherche a besoin
 * de `websearch_to_tsquery` et de `ts_rank` (tri par pertinence), que l'API Criteria ne sait pas
 * exprimer proprement — et Hibernate ne mappe pas le type `tsvector`. Le SQL reste lisible et on
 * garde la maitrise du plan d'execution (index GIN).
 *
 * <p>Securite : tout ce qui vient du client passe en **parametre lie** (`?`) ; les seuls fragments
 * concatenes sont des noms de colonnes issus d'une **whiteliste** (tri).
 */
@Repository
public class TicketSearchRepository {

    /**
     * Colonnes autorisees au tri (le param `sort` vient du client).
     *
     * <p>`slaRisk` y entre au S7-J3. C'est la premiere colonne triable venant d'une jointure, et
     * elle porte un piege : `NULLS LAST` est **obligatoire** en tri descendant, sinon les tickets
     * jamais scores — ceux qui viennent d'arriver — occuperaient le haut de la file « les plus a
     * risque ». Le tri le plus dangereux est celui qui met en tete ce dont on ne sait rien.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "createdAt", "t.created_at",
            "subject", "t.subject",
            "status", "t.status",
            "source", "t.source",
            "language", "t.language",
            "slaDueAt", "t.sla_due_at",
            "slaRisk", "r.risk",
            "id", "t.id");
    private static final Set<String> LANGUAGES = Set.of("fr", "en");

    private final JdbcTemplate jdbc;
    private final double atRiskThreshold;

    public TicketSearchRepository(JdbcTemplate jdbc,
            @Value("${app.sla.at-risk-threshold:0.70}") double atRiskThreshold) {
        this.jdbc = jdbc;
        this.atRiskThreshold = atRiskThreshold;
    }

    public PageResponse<TicketSummaryResponse> search(TicketSearchCriteria c) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        boolean fullText = c.q() != null && !c.q().isBlank();

        if (fullText) {
            // websearch_to_tsquery : syntaxe "grand public" (guillemets, OR, -exclusion) et surtout
            // tolerante — une saisie libre ne provoque jamais d'erreur de parsing (vs to_tsquery).
            // La config doit correspondre a celle de la colonne generee, choisie selon la langue.
            where.append(" AND t.search_vector @@ websearch_to_tsquery(")
                 .append("CASE WHEN t.language = 'en' THEN 'english'::regconfig ELSE 'french'::regconfig END, ?)");
            params.add(c.q().strip());
        }
        if (c.status() != null) {
            where.append(" AND t.status = ?");
            params.add(c.status().name());
        }
        if (c.source() != null) {
            where.append(" AND t.source = ?");
            params.add(c.source().name());
        }
        if (c.language() != null && LANGUAGES.contains(c.language())) {
            where.append(" AND t.language = ?");
            params.add(c.language());
        }
        // Filtres issus de l'analyse IA (table analyses, S3) — desormais disponibles (§6).
        if (c.category() != null) {
            where.append(" AND a.category = ?");
            params.add(c.category());
        }
        if (c.priority() != null) {
            where.append(" AND a.priority = ?");
            params.add(c.priority());
        }
        if (c.sentiment() != null) {
            where.append(" AND a.sentiment = ?");
            params.add(c.sentiment());
        }
        // Filtre « a risque » (S7-J3). Volontairement un booleen et non un seuil libre : le seuil
        // est une decision d'exploitation, pas un reglage par utilisateur. Le laisser choisir a
        // chacun ferait que deux responsables ne parleraient pas de la meme file.
        if (Boolean.TRUE.equals(c.atRisk())) {
            where.append(" AND r.risk >= ?");
            params.add(atRiskThreshold);
        }

        String from = " FROM tickets t"
                + " LEFT JOIN analyses a ON a.ticket_id = t.id"
                // Jointure **externe** : un ticket qui vient d'arriver n'a pas encore de score, et
                // le faire disparaitre de la file serait le pire comportement possible — la liste
                // omettrait silencieusement les tickets les plus recents.
                + " LEFT JOIN sla_risks r ON r.ticket_id = t.id";

        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, params.toArray());
        long totalElements = total == null ? 0 : total;

        // Tri : par pertinence quand une recherche texte est active (le plus utile), sinon colonne whitelistee.
        String orderBy;
        List<Object> selectParams = new ArrayList<>();
        String rankSelect = "";
        if (fullText) {
            rankSelect = ", ts_rank(t.search_vector, websearch_to_tsquery("
                    + "CASE WHEN t.language = 'en' THEN 'english'::regconfig ELSE 'french'::regconfig END, ?)) AS rank";
            selectParams.add(c.q().strip());
            orderBy = " ORDER BY rank DESC, t.created_at DESC";
        } else {
            String column = SORTABLE.getOrDefault(c.sort(), "t.created_at");
            String dir = "asc".equalsIgnoreCase(c.direction()) ? "ASC" : "DESC";
            // NULLS LAST systematique : un ticket sans score (jamais passe dans le lot) ou sans
            // echeance ne doit jamais occuper la tete d'un classement, quel que soit le sens.
            orderBy = " ORDER BY " + column + " " + dir + " NULLS LAST, t.id DESC";
        }

        List<Object> queryParams = new ArrayList<>(selectParams);
        queryParams.addAll(params);
        queryParams.add(c.size());
        queryParams.add(c.page() * c.size());

        // Les colonnes d'analyse viennent de la jointure deja presente pour les filtres : les
        // retourner ne coute aucune requete supplementaire. Elles valent null si le ticket n'a pas
        // encore ete analyse (jointure externe) — la liste affiche alors « en attente ».
        String sql = "SELECT t.id, t.external_ref, t.source, t.customer_email, t.subject, t.body,"
                + " t.language, t.status, t.sla_due_at, t.created_at,"
                + " a.priority, a.category, a.sentiment,"
                + " r.risk AS sla_risk, r.model AS sla_risk_model, r.computed_at AS sla_risk_at"
                + rankSelect
                + from + where + orderBy + " LIMIT ? OFFSET ?";

        List<TicketSummaryResponse> content = jdbc.query(sql, (rs, rowNum) -> new TicketSummaryResponse(
                rs.getLong("id"),
                rs.getString("external_ref"),
                TicketSource.valueOf(rs.getString("source")),
                rs.getString("customer_email"),
                rs.getString("subject"),
                excerpt(rs.getString("body")),
                rs.getString("language"),
                TicketStatus.valueOf(rs.getString("status")),
                toInstant(rs.getTimestamp("sla_due_at")),
                toInstant(rs.getTimestamp("created_at")),
                rs.getString("priority"),
                rs.getString("category"),
                rs.getString("sentiment"),
                // `getBigDecimal` sur un NUMERIC, jamais un cast en Double : c'est exactement le
                // defaut du S4-J4 sur `confidence`, qui ne s'est vu qu'a la premiere ligne reelle.
                rs.getBigDecimal("sla_risk"),
                rs.getString("sla_risk_model"),
                toInstant(rs.getTimestamp("sla_risk_at"))), queryParams.toArray());

        int totalPages = c.size() == 0 ? 0 : (int) Math.ceil((double) totalElements / c.size());
        boolean last = c.page() >= totalPages - 1;
        return new PageResponse<>(content, c.page(), c.size(), totalElements, totalPages, last);
    }

    private static final int EXCERPT_MAX = 160;

    private static String excerpt(String body) {
        if (body == null) {
            return null;
        }
        String flat = body.strip().replaceAll("\\s+", " ");
        return flat.length() <= EXCERPT_MAX ? flat : flat.substring(0, EXCERPT_MAX) + "…";
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
