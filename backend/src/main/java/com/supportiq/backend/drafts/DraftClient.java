package com.supportiq.backend.drafts;

import com.supportiq.backend.common.error.AiServiceException;
import com.supportiq.backend.common.http.RestTemplateFactory;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    public DraftClient(RestTemplateFactory templates,
            @Value("${app.ai-service.base-url}") String baseUrl) {
        // 120 s de lecture : l'appel le plus lent de la plateforme, jusqu'a trois generations plus
        // une auto-verification.
        //
        // Ce client envoyait un corps vide jusqu'au S6-J3, et le defaut y avait dormi plusieurs
        // semaines : la generation de brouillon n'avait jamais ete exercee **depuis l'interface**
        // (le S5-J5 appelait l'agent directement dans le conteneur). Il serait sorti a la premiere
        // demonstration. C'est ce trou precis que `DraftClientTest` ferme.
        this.restTemplate = templates.create(3_000, 120_000);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Genere un brouillon et renvoie son identifiant en base ({@code null} si non persiste). */
    @SuppressWarnings("unchecked")
    public Long generate(long ticketId, String tone) {
        HttpHeaders headers = new HttpHeaders();
        // Charset explicite : sans lui, Spring ecrit un corps String en ISO-8859-1. Sans effet ici
        // (le corps est numerique) mais on ne laisse pas un encodage par defaut dans un client HTTP.
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        String payload = "{\"ticket_id\":" + ticketId + ",\"tone\":\"" + tone + "\"}";

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + "/agents/resolution", HttpMethod.POST,
                    new HttpEntity<>(payload, headers), Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw AiServiceException.draft(HttpStatus.BAD_GATEWAY.value(),
                        "Reponse vide du service d'analyse");
            }
            Object id = body.get("draft_id");
            return id instanceof Number n ? n.longValue() : null;

        } catch (HttpStatusCodeException e) {
            throw translate(e);
        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Service d'analyse injoignable: {}", e.getMessage());
            throw AiServiceException.draft(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "L'assistant de redaction est momentanement indisponible");
        }
    }

    private AiServiceException translate(HttpStatusCodeException e) {
        int status = e.getStatusCode().value();
        log.warn("Service d'analyse: {} sur la generation de brouillon", status);
        if (status == HttpStatus.NOT_FOUND.value()) {
            // Le service IA ne connait pas ce ticket : sans contenu exploitable, il n'y a rien a
            // rediger. C'est une erreur de la demande, pas une panne — le statut doit le refleter.
            return AiServiceException.draft(HttpStatus.CONFLICT.value(),
                    "Ce ticket n'a pas de contenu exploitable pour rediger une reponse");
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return AiServiceException.draft(status, "L'assistant de redaction est momentanement indisponible");
        }
        return AiServiceException.draft(HttpStatus.BAD_GATEWAY.value(),
                "Le service d'analyse a refuse la demande");
    }
}
