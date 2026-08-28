package com.supportiq.backend.alerts;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alertes d'anomalie (S7-J2, rapport §9).
 *
 * <p><b>Racine propre, et non {@code /api/dashboard/alerts}.</b> Le contrat avait ete expose sous le
 * tableau de bord au S4-J1, en avance, quand une alerte n'etait qu'une liste a afficher. Elle porte
 * desormais un acquittement — donc une ressource avec un cycle de vie. La laisser sous le tableau de
 * bord donnerait {@code POST /api/dashboard/alerts/{id}/ack}, qui se lit comme « acquitter un
 * tableau de bord ». Aucun client ne consommait l'ancienne route, le deplacement ne casse rien.
 *
 * <p>Reserve aux <b>MANAGER+</b> : une anomalie de volume porte sur l'activite de toute l'equipe,
 * meme perimetre que le tableau de bord, l'analyse, la synthese et les sujets emergents.
 */
@RestController
@RequestMapping("/api/alerts")
@PreAuthorize("hasRole('MANAGER')")
public class AlertController {

    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    @GetMapping
    public List<Alert> list() {
        return service.recent();
    }

    /**
     * Nombre d'alertes non acquittees.
     *
     * <p>Endpoint separe et volontairement minuscule : l'indicateur de la barre du haut est
     * rafraichi a chaque signal temps reel, et ramener cinquante alertes completes pour afficher un
     * chiffre serait payer la liste a chaque notification.
     */
    @GetMapping("/count")
    public Map<String, Integer> count() {
        return Map.of("open", service.openCount());
    }

    /**
     * Declenchement manuel de la mesure.
     *
     * <p>La detection tourne deja toutes les heures ; ce bouton sert a la demonstration, ou le pic
     * vient d'etre injecte et ou attendre le passage suivant n'aurait pas de sens.
     *
     * @param lookback nombre d'heures a examiner. Au-dela de 1, sert au rattrapage apres un arret.
     */
    @PostMapping("/detect")
    public List<Alert> detect(@RequestParam(defaultValue = "1") int lookback) {
        return service.detect(Math.max(1, Math.min(lookback, 24)));
    }

    /** Prise en charge par un responsable. 409 si quelqu'un d'autre est deja passe avant. */
    @PostMapping("/{id}/ack")
    public Alert acknowledge(@PathVariable long id, Principal principal) {
        return service.acknowledge(id, principal.getName());
    }
}
