package com.supportiq.backend.drafts;

import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Declenche l'agent Resolution du service IA (S5-J4, rapport §6 : {@code POST /agents/resolution}).
 *
 * <p><b>Pourquoi Spring ne fait que declencher.</b> L'agent ecrit lui-meme sa ligne dans
 * {@code draft_responses} (S5-J3) ; le plan de controle n'a donc rien a persister ici. Il relit
 * ensuite la ligne par son identifiant — une seule source de verite, et le brouillon renvoye a
 * l'interface est exactement celui qui est en base, pas une copie reconstruite en memoire.
 *
 * <p><b>Delais.</b> Un brouillon coute jusqu'a trois generations plus une auto-verification : c'est
 * l'appel le plus lent de la plateforme. Un {@code RestTemplate} sans delai d'expiration
 * immobiliserait un fil Tomcat indefiniment si le service IA se bloquait — quelques requetes
 * suffiraient alors a figer tout le backend. La connexion expire vite (le service repond ou non),
 * la lecture est genereuse (le modele reflechit).
 *
 * <p><b>Les echecs ne sont pas avales</b>, contrairement a
 * {@link com.supportiq.backend.tickets.SimilarTicketClient} : generer un brouillon est une action
 * explicite de l'utilisateur. Un echec silencieux laisserait un bouton tourner sans fin.
 */
@Component
public class DraftClient {

    private static final Logger log = LoggerFactory.getLogger(DraftClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public DraftClient(RestTemplateBuilder builder, @Value("${app.ai-service.base-url}") String baseUrl) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(120))
                .build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Genere un brouillon et renvoie son identifiant en base ({@code null} si non persiste). */
    @SuppressWarnings("unchecked")
    public Long generate(long ticketId, String tone) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Corps JSON litteral : meme choix qu'en S4-J4, ou RestClient + Map partait avec un corps
        // vide et FastAPI repondait 422.
        String payload = "{\"ticket_id\":" + ticketId + ",\"tone\":\"" + tone + "\"}";

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + "/agents/resolution", HttpMethod.POST,
                    new HttpEntity<>(payload, headers), Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw new DraftException(HttpStatus.BAD_GATEWAY.value(),
                        "Reponse vide du service d'analyse");
            }
            Object id = body.get("draft_id");
            return id instanceof Number n ? n.longValue() : null;

        } catch (HttpStatusCodeException e) {
            throw translate(e);
        } catch (DraftException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Service d'analyse injoignable: {}", e.getMessage());
            throw new DraftException(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "L'assistant de redaction est momentanement indisponible");
        }
    }

    private DraftException translate(HttpStatusCodeException e) {
        int status = e.getStatusCode().value();
        log.warn("Service d'analyse: {} sur la generation de brouillon", status);
        if (status == HttpStatus.NOT_FOUND.value()) {
            // Le service IA ne connait pas ce ticket : sans contenu exploitable, il n'y a rien a
            // rediger. C'est une erreur de la demande, pas une panne — le statut doit le refleter.
            return new DraftException(HttpStatus.CONFLICT.value(),
                    "Ce ticket n'a pas de contenu exploitable pour rediger une reponse");
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return new DraftException(status, "L'assistant de redaction est momentanement indisponible");
        }
        return new DraftException(HttpStatus.BAD_GATEWAY.value(),
                "Le service d'analyse a refuse la demande");
    }
}
