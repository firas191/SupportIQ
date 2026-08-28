package com.supportiq.backend.intake;

import com.supportiq.backend.common.error.AiServiceException;
import com.supportiq.backend.messaging.TicketCreatedEvent;
import com.supportiq.backend.messaging.TicketsPersistedEvent;
import com.supportiq.backend.tickets.Ticket;
import com.supportiq.backend.tickets.TicketRepository;
import com.supportiq.backend.tickets.TicketSource;
import com.supportiq.backend.tickets.TicketStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingestion de documents non structures : extraction, puis creation apres relecture (S7-J4).
 *
 * <p>Les deux etapes sont **deux appels distincts**, separes par un humain. C'est la meme
 * architecture que le brouillon de reponse (S5-J4) : le modele propose, l'humain tranche. Ici la
 * raison est encore plus directe — un decoupage errone creerait des tickets fantomes qui
 * ressemblent en tout point a de vrais tickets, et que personne ne verrait jamais passer.
 */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    /** 10 Mo, comme la base de connaissances (S5-J1). Un PDF de demandes clients tient largement. */
    private static final int MAX_BYTES = 10 * 1024 * 1024;

    private final IntakeClient client;
    private final TicketRepository tickets;
    private final ApplicationEventPublisher eventPublisher;

    public IntakeService(IntakeClient client, TicketRepository tickets,
            ApplicationEventPublisher eventPublisher) {
        this.client = client;
        this.tickets = tickets;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Extraction. <b>Hors transaction</b> : plusieurs minutes d'appel distant pour zero ecriture.
     * Meme choix qu'au brouillon (S5-J4), au digest (S6-J4) et aux sujets (S7-J1).
     */
    public IntakeModels.ExtractionResult extract(String filename, byte[] content) {
        if (content == null || content.length == 0) {
            throw new AiServiceException(400, "Ingestion documentaire", "intake",
                    "Le fichier est vide.");
        }
        if (content.length > MAX_BYTES) {
            throw new AiServiceException(413, "Ingestion documentaire", "intake",
                    "Fichier trop volumineux (10 Mo maximum).");
        }
        return client.extract(safeName(filename), content);
    }

    /**
     * Creation des tickets valides par l'agent.
     *
     * <p>Une seule transaction et une seule publication d'evenements, **apres commit** : c'est la
     * chaine asynchrone du S2-J3, reutilisee telle quelle. Publier ticket par ticket produirait
     * douze messages la ou un seul lot suffit, et surtout ferait analyser des tickets d'une
     * transaction qui pourrait encore echouer.
     */
    @Transactional
    public IntakeModels.ConfirmResult confirm(IntakeModels.ConfirmRequest request) {
        List<TicketCreatedEvent> events = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        int skipped = 0;

        for (IntakeModels.ConfirmedTicket entry : request.tickets()) {
            String subject = trimTo(entry.subject(), 500);
            if (subject == null) {
                // Le sujet est deja valide par Bean Validation ; ce filet couvre le cas d'une
                // chaine faite uniquement d'espaces, que `@NotBlank` attrape mais qu'un client
                // different pourrait contourner.
                skipped++;
                continue;
            }

            Ticket saved = tickets.save(Ticket.builder()
                    // Pas d'`external_ref` : ces tickets n'existent dans aucun systeme tiers. Leur
                    // en fabriquer une (« PDF-3 ») creerait une fausse clef de deduplication, et
                    // deux imports du meme document seraient alors silencieusement fusionnes —
                    // alors que ce sont peut-etre deux envois legitimes.
                    .source(TicketSource.FILE)
                    .status(TicketStatus.NEW)
                    .customerEmail(blankToNull(entry.customerEmail()))
                    .subject(subject)
                    .body(blankToNull(entry.body()))
                    .language(normalizeLanguage(entry.language()))
                    .build());

            ids.add(saved.getId());
            events.add(new TicketCreatedEvent(saved.getId(), saved.getExternalRef(),
                    saved.getSubject(), saved.getBody(), saved.getLanguage()));
        }

        if (!events.isEmpty()) {
            eventPublisher.publishEvent(new TicketsPersistedEvent(events));
        }
        log.info("Ingestion documentaire : {} tickets crees, {} ignores", ids.size(), skipped);
        return new IntakeModels.ConfirmResult(ids.size(), skipped, ids);
    }

    /**
     * Cree un ticket a partir d'un courriel releve par {@link EmailPoller}.
     *
     * <p><b>Pourquoi cette methode vit ici et non dans le poller.</b> L'evenement
     * {@code TicketsPersistedEvent} est consomme par un
     * {@code @TransactionalEventListener(AFTER_COMMIT)} : publie **hors transaction**, il est
     * simplement ignore, et le ticket ne serait jamais analyse. Il faut donc une transaction — et
     * une methode {@code @Transactional} appelee depuis la meme classe ne passe pas par le proxy
     * Spring, donc l'annotation y serait inerte. En placant la creation dans un autre bean, le
     * proxy s'applique.
     *
     * <p><b>Une transaction par courriel</b>, et non une par releve : un echec sur le septieme
     * message ne doit pas annuler les six premiers, qui ont deja ete marques comme lus cote
     * serveur IMAP. Les annuler creerait exactement ce que le poller cherche a eviter — des
     * messages lus dont aucun ticket ne porte la trace.
     */
    @Transactional
    public Long createFromEmail(String externalRef, String customerEmail, String subject, String body) {
        Ticket saved = tickets.save(Ticket.builder()
                .externalRef(blankToNull(externalRef))
                .source(TicketSource.EMAIL)
                .status(TicketStatus.NEW)
                .customerEmail(blankToNull(customerEmail))
                .subject(trimTo(subject, 500))
                .body(blankToNull(body))
                .build());

        eventPublisher.publishEvent(new TicketsPersistedEvent(List.of(
                new TicketCreatedEvent(saved.getId(), saved.getExternalRef(),
                        saved.getSubject(), saved.getBody(), saved.getLanguage()))));
        return saved.getId();
    }

    /**
     * Neutralise le nom de fichier avant de le transmettre.
     *
     * <p>Meme precaution qu'au S5-J1 : le nom vient du client, il n'a aucune raison de contenir un
     * chemin. Seule l'extension compte pour choisir l'extracteur.
     */
    private static String safeName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "document";
        }
        String base = filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        return base.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String trimTo(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String normalizeLanguage(String value) {
        if (value == null) {
            return null;
        }
        String lang = value.toLowerCase(Locale.ROOT);
        return (lang.equals("fr") || lang.equals("en")) ? lang : null;
    }
}
