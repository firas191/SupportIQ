package com.supportiq.backend.recovery;

import com.supportiq.backend.messaging.RabbitConfig;
import com.supportiq.backend.messaging.TicketCreatedEvent;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Republie les tickets qui n'ont jamais ete analyses (S8-J1).
 *
 * <h2>Le trou que ceci ferme</h2>
 *
 * <p>La publication de {@code ticket.created} est <b>best-effort et posterieure au commit</b>
 * (S2-J3). C'est un choix delibere : l'operation metier — creer un ticket — ne doit jamais dependre
 * de la disponibilite du courtier. Le prix de ce choix est qu'un message perdu ne produit aucune
 * erreur, nulle part, et que le ticket reste sans analyse indefiniment.
 *
 * <p>Le risque etait ecrit dans le rapport du S7-J5 comme theorique. Il s'est realise par un chemin
 * different — RabbitMQ tournait sans volume, la recreation du conteneur a emporte sa base mnesia —
 * a l'echelle de <b>60 016 tickets sur 63 057</b>. Le volume manquant a ete corrige ; ce service
 * traite l'autre moitie du probleme, celle qui restera vraie meme avec un courtier durable : le
 * systeme doit savoir se rattraper, et surtout <b>savoir dire</b> qu'il n'y arrive pas.
 *
 * <h2>Ce qui empeche la boucle infinie</h2>
 *
 * <p>Un balayage naif — « republier tout ticket sans analyse » — rejouerait eternellement les
 * tickets que le pipeline ne sait pas traiter, en consommant du quota LLM tout en paraissant
 * fonctionner. Trois garde-fous, dans {@link AnalysisRecoveryRepository} : periode de grace,
 * plafond de tentatives, delai entre deux tentatives.
 */
@Service
public class AnalysisRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRecoveryService.class);

    private final AnalysisRecoveryRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final Duration grace;
    private final Duration retryAfter;
    private final int maxAttempts;
    private final int batchSize;

    public AnalysisRecoveryService(
            AnalysisRecoveryRepository repository,
            RabbitTemplate rabbitTemplate,
            @Value("${app.analysis-recovery.grace:PT30M}") Duration grace,
            @Value("${app.analysis-recovery.retry-after:PT1H}") Duration retryAfter,
            @Value("${app.analysis-recovery.max-attempts:3}") int maxAttempts,
            @Value("${app.analysis-recovery.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.grace = grace;
        this.retryAfter = retryAfter;
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
    }

    public RecoveryStatus status() {
        return repository.status();
    }

    public int runOnce() {
        return runOnce(batchSize);
    }

    /**
     * Un passage de rattrapage. <b>Volontairement hors transaction.</b>
     *
     * <p>Une transaction englobante n'apporterait rien — RabbitMQ n'y participe pas — et nuirait :
     * elle garderait une connexion du pool ouverte pendant toutes les publications, pour un travail
     * qui n'a aucune raison d'etre atomique. Chaque ticket est independant des autres ; l'echec de
     * l'un ne doit pas annuler les tentatives deja enregistrees pour les precedents.
     *
     * @return le nombre de tickets effectivement republies
     */
    public int runOnce(int limit) {
        // D'abord oublier les tickets finalement analyses : un compteur « en attente » qui ne
        // redescend jamais a zero quand tout va bien cesse tres vite d'etre regarde.
        int forgotten = repository.forgetRecovered();

        List<TicketCreatedEvent> candidates =
                repository.findCandidates(grace, maxAttempts, retryAfter, limit);
        if (candidates.isEmpty()) {
            if (forgotten > 0) {
                log.info("Rattrapage d'analyse : {} ticket(s) rattrape(s), rien a republier", forgotten);
            }
            return 0;
        }

        int published = 0;
        for (TicketCreatedEvent ticket : candidates) {
            try {
                // **La tentative est enregistree AVANT la publication**, et l'ordre est le point
                // delicat de cette methode.
                //
                // Si l'enregistrement reussit et la publication echoue : une tentative est
                // consommee pour rien, le ticket sera reessaye plus tard — perte bornee et visible.
                //
                // Dans l'autre ordre, une publication reussie suivie d'un enregistrement echoue
                // laisserait le ticket sans trace : il redeviendrait candidat au passage suivant,
                // indefiniment. **On prefere toujours le mode de defaillance borne au mode de
                // defaillance qui boucle.**
                repository.recordAttempt(ticket.ticketId(), maxAttempts);
                rabbitTemplate.convertAndSend(
                        RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY_CREATED, ticket);
                published++;
            } catch (Exception e) {
                // Pas de diffusion WebSocket ici, contrairement a `TicketEventPublisher` : ces
                // tickets ne sont pas nouveaux. Annoncer « nouveau ticket » pour une republication
                // ferait clignoter l'interface sur des tickets vieux de plusieurs jours.
                log.warn("Republication impossible pour le ticket {} : {}",
                        ticket.ticketId(), e.getMessage());
            }
        }

        RecoveryStatus status = repository.status();
        log.info("Rattrapage d'analyse : {} republie(s), {} rattrape(s), {} sans analyse",
                published, forgotten, status.unanalysed());

        // **Le seul avertissement de ce service, et il est reserve au cas ou l'automatisme a
        // renonce.** Un ticket abandonne ne sera plus republie : c'est la seule situation qui
        // demande vraiment un humain, parce que le rattrapage a fait tout ce qu'il pouvait et que
        // quelque chose fait echouer l'analyse de facon reproductible.
        //
        // Avertir simplement sur « des tickets sans analyse » serait inutile : ce compteur est non
        // nul en permanence pendant qu'un arriere se resorbe, et un avertissement toujours allume
        // est un avertissement eteint — la lecon des alertes de volume (S7-J2).
        if (status.givenUp() > 0) {
            log.warn("{} ticket(s) abandonne(s) par le rattrapage apres {} tentatives : "
                            + "ils ne seront plus republies. GET /api/admin/analysis-recovery",
                    status.givenUp(), maxAttempts);
        }
        return published;
    }
}
