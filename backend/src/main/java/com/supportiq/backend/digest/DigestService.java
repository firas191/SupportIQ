package com.supportiq.backend.digest;

import com.supportiq.backend.common.error.ResourceNotFoundException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generation, persistance et envoi de la synthese hebdomadaire (S6-J4).
 *
 * <p><b>L'idempotence vit dans la base, pas dans le code.</b> `UNIQUE(week_start)` (migration V12)
 * garantit qu'une semaine n'a qu'un digest, meme si deux instances declenchent au meme instant.
 * Tester « existe-t-il deja ? » avant d'inserer ne suffirait pas : deux noeuds peuvent lire « non »
 * simultanement. Seule la base peut arbitrer, et c'est elle qui le fait.
 */
@Service
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);

    private final DigestRepository repository;
    private final DigestClient client;
    private final DigestMailer mailer;

    public DigestService(DigestRepository repository, DigestClient client, DigestMailer mailer) {
        this.repository = repository;
        this.client = client;
        this.mailer = mailer;
    }

    /** Lundi de la semaine **ecoulee** — celle que couvre le digest du lundi matin. */
    public static LocalDate lastCompletedWeek() {
        return LocalDate.now()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(1);
    }

    public List<Digest> recent() {
        return repository.recent(12);
    }

    public Digest byId(long id) {
        return repository.byId(id)
                .orElseThrow(() -> new ResourceNotFoundException("Digest introuvable : " + id));
    }

    /**
     * Produit le digest d'une semaine s'il n'existe pas, puis tente l'envoi.
     *
     * <p><b>Sans transaction, et deliberement.</b> La generation dure jusqu'a trois minutes
     * (agregats, redaction, rendu PDF) et l'envoi depend d'un serveur externe. Tenir une
     * transaction ouverte pendant tout cela immobiliserait une connexion pour deux ecritures.
     *
     * @param force regenere un digest deja present. Reserve a une demande explicite : le
     *     declenchement automatique ne doit jamais ecraser un digest deja envoye.
     */
    public Digest generate(LocalDate weekStart, boolean force) {
        LocalDate week = weekStart == null ? lastCompletedWeek() : weekStart;

        Optional<Digest> existing = repository.byWeek(week);
        if (existing.isPresent() && !force) {
            return existing.get();
        }

        DigestClient.Report report = client.generate(week);

        long id;
        if (existing.isPresent()) {
            id = existing.get().id();
            repository.replace(id, report.markdown(), report.statsJson());
        } else {
            // Vide = une autre instance vient de l'inserer. Ce n'est pas une erreur : on relit.
            id = repository.insertIfAbsent(week, report.markdown(), report.statsJson())
                    .orElseGet(() -> repository.byWeek(week).map(Digest::id).orElseThrow());
        }

        Digest saved = repository.byId(id).orElseThrow();
        trySend(saved, report.pdf());
        return repository.byId(id).orElseThrow();
    }

    /**
     * Envoie un digest deja genere. Utilise pour reessayer apres un echec.
     *
     * <p>Le PDF est **regenere** a cet instant : il n'est pas stocke (V12), et le Markdown, lui,
     * fait foi. Contrepartie assumee — un renvoi coute un appel au service IA.
     */
    public Digest send(long id) {
        Digest digest = byId(id);
        DigestClient.Report report = client.generate(digest.weekStart());
        trySend(digest, report.pdf());
        return byId(id);
    }

    /**
     * Envoi tolerant : un echec est **trace**, jamais propage.
     *
     * <p>Le digest est deja en base a ce stade. Faire remonter l'erreur ferait echouer la
     * generation entiere alors que le travail est fait et consultable a l'ecran — et sur le
     * declenchement automatique, personne ne serait la pour lire l'exception. La colonne
     * `send_error` rend l'echec visible, ce qui est le seul comportement acceptable pour un
     * courriel qui ne part pas.
     */
    private void trySend(Digest digest, byte[] pdf) {
        if (!mailer.configured()) {
            log.info("Digest {} genere ; envoi non configure (consultable dans l'interface)",
                    digest.weekStart());
            return;
        }
        try {
            mailer.send(digest, pdf);
            repository.markSent(digest.id(), mailer.recipients());
        } catch (RuntimeException e) {
            repository.markFailed(digest.id(), e.getMessage());
        }
    }
}
