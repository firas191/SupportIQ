package com.supportiq.backend.alerts;

import com.supportiq.backend.auth.User;
import com.supportiq.backend.auth.UserRepository;
import com.supportiq.backend.common.error.AiServiceException;
import com.supportiq.backend.realtime.RealtimeBroadcaster;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cycle de vie des alertes : detection, creation, acquittement, diffusion (S7-J2).
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private static final String TYPE_VOLUME = "VOLUME_ANOMALY";
    private static final int LIST_LIMIT = 50;

    private final AlertRepository repository;
    private final AnomalyClient client;
    private final RealtimeBroadcaster broadcaster;
    private final UserRepository users;

    public AlertService(AlertRepository repository, AnomalyClient client,
            RealtimeBroadcaster broadcaster, UserRepository users) {
        this.repository = repository;
        this.client = client;
        this.broadcaster = broadcaster;
        this.users = users;
    }

    public List<Alert> recent() {
        return repository.recent(LIST_LIMIT);
    }

    public int openCount() {
        return repository.countOpen();
    }

    /**
     * Lance une mesure et cree les alertes qui n'existent pas deja.
     *
     * <p>La detection tourne <b>hors transaction</b> : c'est un appel distant, et tenir une
     * connexion ouverte pendant ce temps pour zero ecriture epuiserait le pool. Meme choix qu'au
     * brouillon (S5-J4), au digest (S6-J4) et aux sujets (S7-J1).
     *
     * @return les alertes <b>reellement creees</b>, donc celles qui n'avaient pas deja ete
     *     signalees. C'est cette liste qui est diffusee : rediffuser un pic deja affiche ferait
     *     clignoter l'interface a chaque passage du detecteur.
     */
    public List<Alert> detect(int lookback) {
        List<AnomalyClient.Candidate> candidates = client.detect(lookback);

        List<Alert> created = new ArrayList<>();
        for (AnomalyClient.Candidate candidate : candidates) {
            repository.insertIfAbsent(TYPE_VOLUME, candidate.severity(), candidate.scope(),
                            candidate.bucketStart(), candidate.payloadJson())
                    .flatMap(repository::byId)
                    .ifPresent(created::add);
        }

        if (!created.isEmpty()) {
            log.info("{} nouvelle(s) alerte(s) de volume sur {} pic(s) constate(s)",
                    created.size(), candidates.size());
            created.forEach(this::broadcast);
        }
        return created;
    }

    /**
     * Acquitte une alerte au nom d'un utilisateur.
     *
     * <p>Une alerte deja acquittee renvoie <b>409</b> et non un succes silencieux : deux
     * responsables peuvent cliquer en meme temps, et celui qui arrive second doit savoir que
     * quelqu'un d'autre s'en charge, plutot que de croire qu'il vient de le faire.
     */
    public Alert acknowledge(long id, String userEmail) {
        Alert alert = repository.byId(id).orElseThrow(() -> new AiServiceException(
                404, "Alertes", "alert", "Alerte introuvable"));

        User user = users.findByEmail(userEmail).orElseThrow(() -> new AiServiceException(
                404, "Alertes", "alert", "Utilisateur courant introuvable"));

        if (repository.acknowledge(id, user.getId()) == 0) {
            throw new AiServiceException(409, "Alertes", "alert-state",
                    alert.acknowledged()
                            ? "Cette alerte a deja ete prise en charge"
                            : "L'acquittement n'a pas pu etre enregistre");
        }

        Alert updated = repository.byId(id).orElse(alert);
        // L'acquittement est diffuse comme la creation : sans cela, l'alerte resterait affichee
        // chez les autres responsables, qui continueraient a la traiter.
        broadcast(updated);
        return updated;
    }

    /**
     * Diffusion best-effort sur {@code /topic/alerts}, declare au S4-J5 et jamais alimente jusqu'ici.
     *
     * <p>Le message ne porte que des <b>signaux</b> — identifiant, type, gravite, portee — jamais le
     * detail. C'est la meme regle que pour les tickets : le canal WebSocket est ouvert en
     * {@code permitAll}, donc tout ce qui compte reste derriere l'API protegee, que le client
     * rappelle quand il recoit le signal.
     */
    private void broadcast(Alert alert) {
        broadcaster.alert(Map.of(
                "id", alert.id(),
                "type", alert.type(),
                "severity", alert.severity(),
                "scope", alert.scope(),
                "acknowledged", alert.acknowledged()));
    }
}
