package com.supportiq.backend.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportiq.backend.common.error.AiServiceException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Demande une mesure d'anomalie de volume au service IA (S7-J2).
 *
 * <p>Sixieme client HTTP, ecrit comme les deux precedents avec les corrections du S6-J3 : fabrique
 * de requetes posee explicitement et charset UTF-8 sur le {@code Content-Type}.
 *
 * <p>Le client rapporte des <b>candidates</b>, pas des alertes. La distinction n'est pas
 * rhetorique : le service IA ne sait pas ce qui a deja ete signale, et ne doit pas le savoir — la
 * deduplication demande la table, donc le plan de controle.
 */
@Component
public class AnomalyClient {

    private static final Logger log = LoggerFactory.getLogger(AnomalyClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public AnomalyClient(ObjectMapper mapper, @Value("${app.ai-service.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        // Aucun appel de modele ici : c'est du calcul numerique sur quelques centaines de points.
        // Le delai est donc court — un detecteur qui met une minute a repondre a un probleme.
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Un pic constate, avant toute decision de creation d'alerte. */
    public record Candidate(String scope, Instant bucketStart, String severity, String payloadJson) {
    }

    @SuppressWarnings("unchecked")
    public List<Candidate> detect(int lookback) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));

        Map<String, Object> request = new HashMap<>();
        request.put("window_hours", null);   // laisse le service IA appliquer sa configuration
        request.put("lookback", lookback);

        String body;
        try {
            body = mapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new AiServiceException(500, "Detection d'anomalies", "anomaly", "Demande illisible");
        }

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + "/anomaly/detect", HttpMethod.POST,
                    new HttpEntity<>(body, headers), Map.class);

            Map<String, Object> map = response.getBody();
            if (map == null) {
                throw new AiServiceException(502, "Detection d'anomalies", "anomaly",
                        "Reponse vide du service d'analyse");
            }

            List<Map<String, Object>> raw =
                    (List<Map<String, Object>>) map.getOrDefault("anomalies", List.of());
            return raw.stream().map(this::toCandidate).toList();

        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Detection d'anomalies impossible: {}", e.getMessage());
            throw new AiServiceException(503, "Detection d'anomalies", "anomaly",
                    "Le service d'analyse est momentanement indisponible");
        }
    }

    private Candidate toCandidate(Map<String, Object> row) {
        Object payload = row.getOrDefault("payload", Map.of());
        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            // Le detail chiffre est un confort ; le pic constate est l'information. On garde
            // l'alerte avec un payload vide plutot que de la perdre pour un probleme de forme.
            payloadJson = "{}";
        }
        return new Candidate(
                String.valueOf(row.get("scope")),
                // `OffsetDateTime.parse` et non `Instant.parse` : Python produit « +00:00 », que
                // `Instant.parse` refuse (il n'accepte que le suffixe « Z »). Le detail se voit a la
                // premiere reponse reelle, jamais a la compilation — comme le NUMERIC du S4-J4.
                OffsetDateTime.parse(String.valueOf(row.get("bucket_start"))).toInstant(),
                String.valueOf(row.getOrDefault("severity", "WARNING")),
                payloadJson);
    }
}
