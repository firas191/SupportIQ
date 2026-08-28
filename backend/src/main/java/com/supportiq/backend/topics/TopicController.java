package com.supportiq.backend.topics;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sujets emergents (S7-J1, rapport §9).
 *
 * <p>Reserve aux <b>MANAGER+</b>, meme perimetre que le tableau de bord, le chat Insight et le
 * digest : ces trois ecrans agregent l'activite de toute l'equipe. Un agent y verrait la charge de
 * ses collegues sans que cela l'aide a traiter son propre ticket.
 */
@RestController
@RequestMapping("/api/topics")
@PreAuthorize("hasRole('MANAGER')")
public class TopicController {

    private final TopicService service;

    public TopicController(TopicService service) {
        this.service = service;
    }

    @GetMapping
    public TopicService.Snapshot latest() {
        return service.latest();
    }

    /**
     * Bouton « recalculer ».
     *
     * <p>Le calcul est deja programme chaque nuit ; ce declenchement existe pour la demonstration
     * et pour le lendemain d'un import massif, ou attendre la nuit n'a pas de sens.
     *
     * @param windowDays fenetre en jours. Absente = la valeur configuree. Changer la fenetre change
     *     le sens de la croissance (elle compare les deux moities de la fenetre) : deux instantanes
     *     de fenetres differentes ne se comparent pas.
     */
    @PostMapping("/detect")
    public TopicService.Snapshot detect(@RequestParam(required = false) Integer windowDays) {
        return service.detect(windowDays);
    }
}
