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
 *
 * <h2>Jointure differee (S7-J5) — et l'erreur de raisonnement qu'elle corrige</h2>
 *
 * <p>La V18 a ajoute {@code ix_tickets_status_created (status, created_at DESC, id DESC)} en
 * affirmant que le filtre deviendrait un parcours de plage, que l'ordre viendrait de l'index et que
 * le {@code LIMIT 20} s'arreterait apres vingt lignes. <b>Les plans d'execution ont montre que
 * l'index n'etait jamais choisi.</b>
 *
 * <p>La raison n'est pas l'index, c'est la forme de la requete : le {@code ORDER BY} s'applique
 * <b>apres</b> les deux {@code LEFT JOIN}. PostgreSQL joignait donc les 20 557 lignes filtrees, puis
 * triait, puis en gardait vingt. Aucun index ne peut eviter un travail que la requete reclame
 * explicitement dans cet ordre.
 *
 * <p>La requete selectionne desormais les <b>identifiants</b> de la page — filtre, tri et limite sur
 * la seule table {@code tickets} — puis ne joint que ces vingt lignes. Mesure sur 63 057 tickets,
 * 32 % au statut NEW :
 *
 * <pre>
 *   avant : Seq Scan + 2 hash joins + tri sur 20 557 lignes   45,7 ms   8 532 pages
 *   apres : Index Only Scan (Heap Fetches 0) + 3 nested loops   3,7 ms     251 pages
 * </pre>
 *
 * <p><b>Les jointures ne sont incluses dans la sous-requete que si elles y servent</b> — un filtre
 * de categorie ou un tri par risque SLA les rend necessaires. Les ajouter systematiquement
 * annulerait le gain, puisque c'est precisement de ne pas joindre que vient l'{@code Index Only
 * Scan}.
 *
 * <p>Ce que cela ne corrige pas : la <b>pagination par offset</b>. {@code OFFSET 10000} oblige
 * toujours a produire puis jeter dix mille identifiants — moins cher qu'avant, puisqu'ils ne sont
 * plus joints, mais toujours lineaire. La vraie reponse serait une pagination par curseur, donc un
 * changement de contrat d'API.
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

    private static final String JOIN_ANALYSES = " LEFT JOIN analyses a ON a.ticket_id = t.id";
    // Jointure **externe** : un ticket qui vient d'arriver n'a pas encore de score, et le faire
    // disparaitre de la file serait le pire comportement possible — la liste omettrait
    // silencieusement les tickets les plus recents.
    private static final String JOIN_SLA = " LEFT JOIN sla_risks r ON r.ticket_id = t.id";

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
            //
            // **Deux branches a configuration constante, et non un CASE a l'interieur de l'appel**
            // (correctif S7-J5). La forme precedente placait le CASE *dans* websearch_to_tsquery :
            // le cote droit du `@@` dependait alors de `t.language`, donc de la ligne en cours. Un
            // index se parcourt avec une cle ; sans cle constante, PostgreSQL n'avait d'autre choix
            // que de lire la table entiere en appelant la fonction a chaque ligne.
            //
            // Le defaut a survecu a la verification du S4-J3 parce que l'EXPLAIN de l'epoque avait
            // ete ecrit a la main avec 'french' en dur — un plan reel, sur une requete que
            // l'application n'execute pas. Il a fallu un tir de charge pour le voir : seul, le plan
            // sequentiel se parallelise et tient 79 ms ; a dix utilisateurs, chacun reclamant ses
            // processus auxiliaires, le P95 montait a 573 ms.
            //
            // `IS DISTINCT FROM` et non `<>` : `language` est nullable, et `NULL <> 'en'` vaut NULL.
            // Avec un simple `<>`, tout ticket dont la langue n'a pas ete detectee disparaitrait
            // silencieusement des resultats — une perte invisible sur n'importe quel tableau de
            // latence.
            where.append(" AND ((t.language = 'en'")
                 .append(" AND t.search_vector @@ websearch_to_tsquery('english'::regconfig, ?))")
                 .append(" OR (t.language IS DISTINCT FROM 'en'")
                 .append(" AND t.search_vector @@ websearch_to_tsquery('french'::regconfig, ?)))");
            params.add(c.q().strip());
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

        // Tri : par pertinence quand une recherche texte est active (le plus utile), sinon colonne
        // whitelistee.
        String sortColumn = SORTABLE.getOrDefault(c.sort(), "t.created_at");

        // Une jointure n'entre dans la sous-requete que si le filtre ou le tri l'exige. C'est la
        // condition du gain : sans elles, la sous-requete se resout entierement dans l'index
        // (`Heap Fetches: 0`).
        boolean needsAnalyses = c.category() != null || c.priority() != null || c.sentiment() != null;
        boolean needsSlaRisks = Boolean.TRUE.equals(c.atRisk()) || sortColumn.startsWith("r.");

        String innerFrom = " FROM tickets t"
                + (needsAnalyses ? JOIN_ANALYSES : "")
                + (needsSlaRisks ? JOIN_SLA : "");

        // Le COUNT porte le meme FROM reduit. PostgreSQL sait eliminer une jointure externe inutile
        // (`analyses.ticket_id` est UNIQUE, `sla_risks.ticket_id` est PK, donc au plus une ligne
        // jointe), mais lui epargner le travail vaut mieux que de compter dessus.
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + innerFrom + where, Long.class, params.toArray());
        long totalElements = total == null ? 0 : total;

        String innerOrderBy;
        String outerOrderBy;
        List<Object> selectParams = new ArrayList<>();
        String rankSelect = "";
        if (fullText) {
            // Meme correction que dans le WHERE, pour la meme raison : le CASE est **autour** des
            // appels et non dedans. Les deux `websearch_to_tsquery` sont alors des constantes,
            // evaluees une fois au lieu d'une par ligne survivante ; le CASE ne fait plus que
            // choisir laquelle.
            rankSelect = ", ts_rank(t.search_vector, CASE WHEN t.language = 'en'"
                    + " THEN websearch_to_tsquery('english'::regconfig, ?)"
                    + " ELSE websearch_to_tsquery('french'::regconfig, ?) END) AS rank";
            selectParams.add(c.q().strip());
            selectParams.add(c.q().strip());
            innerOrderBy = " ORDER BY rank DESC, t.created_at DESC";
            // La pertinence est calculee une seule fois, dans la sous-requete ; l'exterieur se
            // contente de preserver l'ordre des vingt identifiants deja choisis.
            outerOrderBy = " ORDER BY k.rank DESC, t.created_at DESC";
        } else {
            String dir = "asc".equalsIgnoreCase(c.direction()) ? "ASC" : "DESC";
            // NULLS LAST systematique : un ticket sans score (jamais passe dans le lot) ou sans
            // echeance ne doit jamais occuper la tete d'un classement, quel que soit le sens.
            innerOrderBy = " ORDER BY " + sortColumn + " " + dir + " NULLS LAST, t.id DESC";
            // Le meme tri est rejoue a l'exterieur : une jointure ne garantit aucun ordre, et vingt
            // lignes deja en memoire coutent un tri negligeable.
            outerOrderBy = innerOrderBy;
        }

        List<Object> queryParams = new ArrayList<>(selectParams);
        queryParams.addAll(params);
        queryParams.add(c.size());
        queryParams.add(c.page() * c.size());

        // Etape 1 : les identifiants de la page. Lignes etroites, aucune jointure superflue.
        String inner = "SELECT t.id" + rankSelect + innerFrom + where + innerOrderBy + " LIMIT ? OFFSET ?";

        // Etape 2 : les colonnes completes, sur ces vingt lignes seulement. Les colonnes d'analyse
        // valent null si le ticket n'a pas encore ete analyse (jointure externe) — la liste affiche
        // alors « en attente ».
        String sql = "SELECT t.id, t.external_ref, t.source, t.customer_email, t.subject, t.body,"
                + " t.language, t.status, t.sla_due_at, t.created_at,"
                + " a.priority, a.category, a.sentiment,"
                + " r.risk AS sla_risk, r.model AS sla_risk_model, r.computed_at AS sla_risk_at"
                + " FROM (" + inner + ") k"
                + " JOIN tickets t ON t.id = k.id"
                + JOIN_ANALYSES + JOIN_SLA
                + outerOrderBy;

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
