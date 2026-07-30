package com.supportiq.backend.tickets;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Client du service IA pour les tickets similaires (S4-J4).
 *
 * <p>Respecte la frontiere du rapport §6 : le calcul vectoriel (pgvector, embeddings) appartient au
 * **plan de calcul** FastAPI ; Spring, plan de controle, l'appelle via HTTP plutot que de refaire la
 * requete `<=>` de son cote (une seule implementation de la regle de doublon).
 *
 * <p>Implementation : `RestTemplate` + `HttpEntity` avec un corps JSON **litteral**. C'est verbeux
 * mais totalement previsible — un `RestClient` avec une Map partait avec un corps vide (FastAPI
 * repondait 422 « Field required »).
 *
 * <p>Resilience : la similarite est un **enrichissement**, pas le cœur de la fiche. Si le service IA
 * est indisponible, on renvoie une liste vide et la fiche s'affiche quand meme.
 */
@Component
public class SimilarTicketClient {

    private static final Logger log = LoggerFactory.getLogger(SimilarTicketClient.class);
    private static final int DEFAULT_K = 5;

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;

    public SimilarTicketClient(@Value("${app.ai-service.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @SuppressWarnings("unchecked")
    public List<TicketDetailResponse.SimilarTicket> findSimilar(long ticketId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String payload = "{\"ticket_id\":" + ticketId + ",\"k\":" + DEFAULT_K + "}";

            ResponseEntity<List> response = restTemplate.exchange(
                    baseUrl + "/similar", HttpMethod.POST,
                    new HttpEntity<>(payload, headers), List.class);

            List<Map<String, Object>> body = response.getBody();
            if (body == null) {
                return List.of();
            }
            return body.stream().map(SimilarTicketClient::toSimilar).toList();
        } catch (Exception e) {  // service IA down, timeout, ticket sans embedding...
            log.warn("Similaires indisponibles pour le ticket {} : {}", ticketId, e.getMessage());
            return List.of();
        }
    }

    private static TicketDetailResponse.SimilarTicket toSimilar(Map<String, Object> m) {
        Number id = (Number) m.get("ticket_id");
        Number similarity = (Number) m.get("similarity");
        return new TicketDetailResponse.SimilarTicket(
                id == null ? null : id.longValue(),
                (String) m.get("subject"),
                (String) m.get("category"),
                similarity == null ? null : similarity.doubleValue(),
                Boolean.TRUE.equals(m.get("is_duplicate")));
    }
}
