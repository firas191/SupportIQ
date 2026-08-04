package com.supportiq.backend.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lecture de la base de connaissances (S5-J1).
 *
 * <p><b>Pourquoi Spring lit directement la table</b> alors que c'est FastAPI qui l'ecrit : lister
 * des documents est une simple agregation SQL, sans le moindre calcul vectoriel. Passer par un appel
 * HTTP au service IA ajouterait une dependance reseau — et donc une page d'administration en panne
 * des que le service IA redemarre — pour rigoureusement aucun gain.
 *
 * <p>La regle appliquee depuis S3 reste la meme : ce qui demande un **modele** part au plan de
 * calcul, ce qui demande une **requete** reste au plan de controle.
 *
 * <p>JdbcTemplate et non JPA, comme pour {@code analyses} et les vues du dashboard : agregat en
 * lecture seule, sans identite ni cycle de vie d'entite.
 */
@Repository
public class KbRepository {

    private final JdbcTemplate jdbc;

    public KbRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Un enregistrement par document.
     *
     * <p>{@code COUNT(vector)} ne compte que les vecteurs non nuls : l'ecart avec {@code COUNT(*)}
     * revele les fragments non embeddes — stockes mais invisibles a la recherche. C'est precisement
     * l'information qui declenche une re-indexation.
     */
    public List<KbDocumentResponse> listDocuments() {
        return jdbc.query(
                """
                SELECT source,
                       MIN(title)      AS title,
                       COUNT(*)        AS chunks,
                       COUNT(vector)   AS indexed,
                       MAX(updated_at) AS updated_at
                FROM kb_documents
                GROUP BY source
                ORDER BY MAX(updated_at) DESC
                """,
                (rs, rowNum) -> new KbDocumentResponse(
                        rs.getString("source"),
                        rs.getString("title"),
                        rs.getInt("chunks"),
                        rs.getInt("indexed"),
                        toInstant(rs.getTimestamp("updated_at"))));
    }

    /** Nombre total de fragments, pour l'entete de l'ecran. */
    public int countChunks() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM kb_documents", Integer.class);
        return count == null ? 0 : count;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
