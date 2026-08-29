package com.supportiq.backend.recovery;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Etat et declenchement du rattrapage d'analyse (S8-J1).
 *
 * <p><b>ADMIN.</b> Ce n'est pas une vue metier : rien ici ne parle de tickets a traiter, tout parle
 * de sante de la plateforme. Un responsable d'equipe n'a rien a faire d'un compteur de messages
 * perdus, et ne saurait pas quoi en faire.
 *
 * <p>Deux endpoints, et le premier compte plus que le second. Le defaut d'origine n'etait pas que
 * des tickets echappent au pipeline — c'est que <b>rien ne le disait</b>. Un rattrapage silencieux
 * qui fonctionnerait a moitie reproduirait exactement le probleme qu'il pretend corriger.
 */
@RestController
@RequestMapping("/api/admin/analysis-recovery")
@PreAuthorize("hasRole('ADMIN')")
public class AnalysisRecoveryController {

    /**
     * Plafond du declenchement manuel. Le but est de pouvoir resorber un arriere sans y passer la
     * journee, pas d'offrir un moyen commode d'envoyer 60 000 analyses d'un clic.
     */
    private static final int MAX_MANUAL_BATCH = 2000;

    private final AnalysisRecoveryService service;

    public AnalysisRecoveryController(AnalysisRecoveryService service) {
        this.service = service;
    }

    @GetMapping
    public RecoveryStatus status() {
        return service.status();
    }

    /**
     * Force un passage, avec une limite explicite.
     *
     * <p>Explicite et non implicite : resorber un arriere consomme du quota LLM, donc c'est une
     * decision. Elle se prend en connaissance de cause, elle ne se subit pas au reveil d'un
     * ordonnanceur.
     */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam(defaultValue = "200") int limit) {
        int bounded = Math.clamp(limit, 1, MAX_MANUAL_BATCH);
        int published = service.runOnce(bounded);
        // `requested` est renvoye a cote de `published` : sans lui, une limite ecretee passerait
        // inapercue et on croirait la file vide. Meme correctif qu'au S5-J5, ou l'echantillon
        // demande (50) et obtenu (8) divergeaient en silence.
        return Map.of("requested", bounded,
                "published", published,
                "status", service.status());
    }
}
