package com.supportiq.backend.insight;

import com.supportiq.backend.common.error.AiServiceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chat Insight — un manager pose une question, la plateforme repond (S6-J3, rapport §9).
 *
 * <p><b>Reserve aux MANAGER+</b>, comme le tableau de bord : ces vues agregent l'activite de toute
 * l'equipe. Un agent y verrait le volume traite par ses collegues, ce qui n'est pas son metier.
 *
 * <p>Le <b>quota horaire</b> ferme la derniere dette identifiee dans l'ADR-0007. Chaque question
 * coute jusqu'a quatre appels de modele : sans borne, une boucle accidentelle epuise le budget de
 * jetons de la journee.
 */
@RestController
@RequestMapping("/api/insight")
@PreAuthorize("hasRole('MANAGER')")
public class InsightController {

    private final InsightClient client;
    private final InsightRateLimiter rateLimiter;

    public InsightController(InsightClient client, InsightRateLimiter rateLimiter) {
        this.client = client;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Question en langage naturel.
     *
     * @param request question bornee a 500 caracteres — une question de manager tient en une
     *     phrase ; au-dela ce n'est plus une question, c'est du contexte destine a noyer
     *     l'instruction systeme.
     */
    @PostMapping("/questions")
    public InsightAnswer ask(@Valid @RequestBody QuestionRequest request, Authentication auth) {
        if (!rateLimiter.tryConsume(auth.getName())) {
            throw AiServiceException.insight(HttpStatus.TOO_MANY_REQUESTS.value(),
                    "Trop de questions posees en peu de temps. Reessayez dans quelques minutes.");
        }
        return client.ask(request.question(), topRole(auth));
    }

    /**
     * Role transmis au service IA pour respecter le contrat §6. Il n'y sert <b>pas</b>
     * d'autorisation : celle-ci vient d'etre appliquee ci-dessus par {@code @PreAuthorize}. Un
     * service interne qui se fierait a un role transmis dans un corps JSON n'aurait aucune securite.
     */
    private static String topRole(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .findFirst()
                .orElse("MANAGER");
    }

    public record QuestionRequest(@NotBlank @Size(max = 500) String question) {
    }
}
