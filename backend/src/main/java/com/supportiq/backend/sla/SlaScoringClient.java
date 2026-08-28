package com.supportiq.backend.sla;

import com.supportiq.backend.common.error.AiServiceException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Demande au service IA de recalculer le risque SLA des tickets ouverts (S7-J3).
 *
 * <p>Septieme client HTTP, ecrit avec les corrections du S6-J3 dans le constructeur.
 *
 * <p>Le corps est vide : le service IA sait quels tickets sont ouverts, il lit la base. Envoyer la
 * liste depuis Spring ferait transiter 5 000 identifiants par HTTP pour une information deja
 * presente des deux cotes.
 */
@Component
public class SlaScoringClient {

    private static final Logger log = LoggerFactory.getLogger(SlaScoringClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public SlaScoringClient(@Value("${app.ai-service.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        // Scoring par lots sur quelques milliers de lignes : pas d'appel de modele de langage, mais
        // une requete large et une prediction par ticket.
        factory.setReadTimeout(60_000);
        this.restTemplate = new RestTemplate(factory);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Compte rendu du recalcul.
     *
     * @param model {@code lightgbm} ou {@code rules}. Remonte jusqu'ici pour que l'interface puisse
     *     dire d'ou vient le chiffre — un score de repli ne se lit pas comme une prediction.
     */
    public record Result(int scored, String model, int atRisk) {
    }

    @SuppressWarnings("unchecked")
    public Result score() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + "/sla/score", HttpMethod.POST,
                    new HttpEntity<>("{}", headers), Map.class);

            Map<String, Object> map = response.getBody();
            if (map == null) {
                throw new AiServiceException(502, "Risque SLA", "sla",
                        "Reponse vide du service d'analyse");
            }
            return new Result(
                    asInt(map.get("scored")),
                    String.valueOf(map.getOrDefault("model", "rules")),
                    asInt(map.get("at_risk")));

        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Recalcul du risque SLA impossible: {}", e.getMessage());
            throw new AiServiceException(503, "Risque SLA", "sla",
                    "Le service d'analyse est momentanement indisponible");
        }
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
