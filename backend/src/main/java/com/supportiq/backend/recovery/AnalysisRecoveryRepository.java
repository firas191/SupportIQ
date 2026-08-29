package com.supportiq.backend.recovery;

import com.supportiq.backend.messaging.TicketCreatedEvent;
import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acces aux tickets echappes au pipeline d'analyse (S8-J1).
 *
 * <p>JdbcTemplate et non JPA : ce sont des requetes d'ensemble sur une jointure externe et une table
 * annexe sans identite metier — la meme frontiere que {@code analyses}, {@code sla_risks} ou
 * {@code topics} depuis la semaine 3.
 */
@Repository
public class AnalysisRecoveryRepository {

    /**
     * Selection des candidats a republication.
     *
     * <p>Quatre conditions, chacune pour une raison distincte :
     * <ul>
     *   <li><b>aucune analyse</b> — la definition meme du probleme ;</li>
     *   <li><b>plus vieux que la periode de grace</b> — un ticket cree il y a trente secondes a
     *       simplement son message en vol. Sans cette condition, le balayage doublerait le trafic
     *       nominal et se prendrait pour un correctif ;</li>
     *   <li><b>tentatives sous le plafond, statut PENDING</b> — un ticket que l'analyse ne sait pas
     *       traiter ne doit pas etre rejoue indefiniment. {@code r.ticket_id IS NULL} couvre le cas
     *       majoritaire : jamais vu par le rattrapage ;</li>
     *   <li><b>pas retente trop recemment</b> — sans ce delai, un arriere important serait republie
     *       a chaque passage avant meme d'avoir ete consomme, et la file grossirait plus vite
     *       qu'elle ne se vide.</li>
     * </ul>
     *
     * <p><b>Tri du plus recent au plus ancien.</b> Un ticket arrive il y a une heure et non analyse
     * est un probleme d'exploitation ; un ticket de l'an dernier est de l'archeologie. C'est aussi
     * la lecon du lot de scoring SLA (session de verification S7), trie par echeance la plus vieille
     * d'abord — donc rempli des cas les moins informatifs.
     */
    private static final String SELECT_CANDIDATES = """
            SELECT t.id, t.external_ref, t.subject, t.body, t.language
            FROM tickets t
            LEFT JOIN analyses a ON a.ticket_id = t.id
            LEFT JOIN analysis_recovery r ON r.ticket_id = t.id
            WHERE a.ticket_id IS NULL
              AND t.created_at < now() - make_interval(secs => ?)
              AND (r.ticket_id IS NULL
                   OR (r.status = 'PENDING'
                       AND r.attempts < ?
                       AND (r.last_attempt_at IS NULL
                            OR r.last_attempt_at < now() - make_interval(secs => ?))))
            ORDER BY t.created_at DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public AnalysisRecoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TicketCreatedEvent> findCandidates(Duration grace, int maxAttempts,
            Duration retryAfter, int limit) {
        return jdbc.query(SELECT_CANDIDATES, (rs, i) -> new TicketCreatedEvent(
                        rs.getLong("id"),
                        rs.getString("external_ref"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getString("language")),
                (double) grace.toSeconds(), maxAttempts, (double) retryAfter.toSeconds(), limit);
    }

    /**
     * Enregistre une tentative, et bascule en {@code GIVEN_UP} quand le plafond est atteint.
     *
     * <p>Le passage a GIVEN_UP est fait <b>ici</b>, dans le meme ordre SQL que l'incrementation, et
     * non par une passe ulterieure : un ticket dont on a epuise les tentatives doit cesser d'etre
     * candidat au moment exact ou la derniere est consommee. Deux instances qui balaieraient en
     * parallele ne peuvent pas non plus le faire deriver.
     *
     * <p><b>Abandonner n'est pas effacer.</b> La ligne reste, avec son compteur : c'est elle qui
     * rendra le probleme visible dans l'etat expose. Le defaut d'origine n'etait pas que des tickets
     * echappent au pipeline — c'est que personne ne pouvait le savoir.
     */
    public void recordAttempt(long ticketId, int maxAttempts) {
        jdbc.update("""
                INSERT INTO analysis_recovery (ticket_id, status, attempts, last_attempt_at)
                VALUES (?, 'PENDING', 1, now())
                ON CONFLICT (ticket_id) DO UPDATE
                SET attempts        = analysis_recovery.attempts + 1,
                    last_attempt_at = now(),
                    status          = CASE WHEN analysis_recovery.attempts + 1 >= ?
                                           THEN 'GIVEN_UP' ELSE 'PENDING' END
                WHERE analysis_recovery.status = 'PENDING'
                """, ticketId, maxAttempts);
    }

    /**
     * Retire les lignes des tickets finalement analyses.
     *
     * <p>Sans ce nettoyage, la table conserverait indefiniment les tickets rattrapes avec succes, et
     * le compteur « en attente » ne redescendrait jamais — un indicateur qui ne revient pas a zero
     * quand tout va bien cesse tres vite d'etre regarde.
     */
    public int forgetRecovered() {
        return jdbc.update("""
                DELETE FROM analysis_recovery r
                WHERE r.status <> 'OUT_OF_SCOPE'
                  AND EXISTS (SELECT 1 FROM analyses a WHERE a.ticket_id = r.ticket_id)
                """);
    }

    /**
     * Etat du dispositif, en une requete.
     *
     * <p>{@code unanalysed} exclut volontairement les tickets hors perimetre : un chiffre qui
     * compterait 50 000 tickets de test resterait alarmant en permanence, donc ne signalerait plus
     * rien. C'est le meme raisonnement que partout ailleurs dans ce projet — une alerte toujours
     * allumee est une alerte eteinte.
     *
     * <p><b>{@code pending} et {@code givenUp} excluent les tickets finalement analyses</b>, sans
     * attendre que {@link #forgetRecovered()} soit passe. Constate en recette : les 50 tickets du
     * premier lot etaient analyses, la file vide, et l'endpoint annoncait encore « 50 en attente »
     * — parce que le menage n'a lieu qu'au debut du passage suivant, un quart d'heure plus tard.
     *
     * <p>La correction n'est pas d'appeler le nettoyage depuis une lecture (une lecture ne modifie
     * pas l'etat), mais de <b>deriver le chiffre affiche de la verite plutot que d'un menage suppose
     * fait</b>. {@code forgetRecovered} redevient ce qu'il doit etre : de l'entretien, dont
     * l'exactitude de l'indicateur ne depend pas. Pour une fonctionnalite dont l'unique but est
     * d'etre crue, un compteur faux pendant quinze minutes est disqualifiant.
     */
    public RecoveryStatus status() {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM tickets t
                     LEFT JOIN analyses a ON a.ticket_id = t.id
                     LEFT JOIN analysis_recovery r ON r.ticket_id = t.id
                     WHERE a.ticket_id IS NULL
                       AND (r.status IS NULL OR r.status <> 'OUT_OF_SCOPE'))       AS unanalysed,
                    (SELECT COUNT(*) FROM analysis_recovery r
                     WHERE r.status = 'PENDING'
                       AND NOT EXISTS (SELECT 1 FROM analyses a
                                       WHERE a.ticket_id = r.ticket_id))           AS pending,
                    (SELECT COUNT(*) FROM analysis_recovery r
                     WHERE r.status = 'GIVEN_UP'
                       AND NOT EXISTS (SELECT 1 FROM analyses a
                                       WHERE a.ticket_id = r.ticket_id))           AS given_up,
                    (SELECT COUNT(*) FROM analysis_recovery WHERE status = 'OUT_OF_SCOPE') AS out_of_scope
                """, (rs, i) -> new RecoveryStatus(
                rs.getLong("unanalysed"),
                rs.getLong("pending"),
                rs.getLong("given_up"),
                rs.getLong("out_of_scope")));
    }
}
