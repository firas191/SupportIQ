package com.supportiq.backend.topics;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lecture des sujets emergents (S7-J1).
 *
 * <p>La table {@code topics} est <b>ecrite par FastAPI</b> et lue ici en direct, comme
 * {@code analyses}, {@code embeddings} et {@code kb_documents} depuis la semaine 3. La frontiere du
 * rapport §6 tient : le regroupement est un calcul, il reste au plan de calcul ; lire un
 * instantane deja calcule est une requete, pas un calcul.
 *
 * <p>Faire transiter cette lecture par HTTP couterait un aller-retour et rendrait l'ecran
 * inutilisable des que le service IA redemarre — alors meme que les donnees, elles, sont la.
 */
@Repository
public class TopicRepository {

    private final JdbcTemplate jdbc;

    public TopicRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Sujets du <b>dernier</b> instantane, les plus en croissance d'abord.
     *
     * <p>La sous-requete sur {@code MAX(computed_at)} est ce qui garantit qu'on ne melange jamais
     * deux executions. Melanger reviendrait a afficher le meme sujet deux fois avec des chiffres
     * differents — et rien, dans l'ecran, ne permettrait de comprendre pourquoi.
     *
     * <p>Le tri place les croissances connues avant les nouveaux sujets ({@code growth IS NULL}),
     * puis les plus fortes d'abord. Ce n'est pas le tri du tableau de bord (qui classe par volume) :
     * la question posee ici n'est pas « qu'est-ce qui est gros » mais « qu'est-ce qui bouge ».
     */
    public List<Topic> latest() {
        return jdbc.query("""
                SELECT id, computed_at, window_days, label, size,
                       recent_count, previous_count, growth, sample_ticket_ids, top_category
                FROM topics
                WHERE computed_at = (SELECT MAX(computed_at) FROM topics)
                ORDER BY growth DESC NULLS LAST, size DESC
                """, this::map);
    }

    private Topic map(ResultSet rs, int rowNum) throws SQLException {
        return new Topic(
                rs.getLong("id"),
                toInstant(rs.getTimestamp("computed_at")),
                rs.getInt("window_days"),
                rs.getString("label"),
                rs.getInt("size"),
                rs.getInt("recent_count"),
                rs.getInt("previous_count"),
                // `getBigDecimal` et non un cast en Double : `growth` est un NUMERIC, et lire un
                // NUMERIC comme un Double leve une ClassCastException a l'execution. Exactement le
                // defaut trouve au S4-J4 sur `confidence` — il ne se voit qu'a la premiere ligne
                // reelle, jamais a la compilation.
                rs.getBigDecimal("growth"),
                toIds(rs.getArray("sample_ticket_ids")),
                rs.getString("top_category"));
    }

    private static List<Long> toIds(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            if (value != null) {
                ids.add(((Number) value).longValue());
            }
        }
        return ids;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
