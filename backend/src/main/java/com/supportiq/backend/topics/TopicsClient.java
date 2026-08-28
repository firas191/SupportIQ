package com.supportiq.backend.topics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportiq.backend.common.error.AiServiceException;
import com.supportiq.backend.common.http.RestTemplateFactory;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
 * Declenche une detection de sujets emergents cote service IA (S7-J1).
 *
 * <p>Cinquieme client HTTP, ecrit avec les deux corrections du S6-J3 dans le constructeur :
 * fabrique de requetes posee <b>explicitement</b> (un {@code RestTemplateBuilder} produisait un
 * client envoyant un corps vide, ce qui a coute une soiree) et charset UTF-8 sur le
 * {@code Content-Type}.
 *
 * <p>Le client ne recupere <b>pas</b> les sujets : le service IA ecrit la table {@code topics} et
 * ne renvoie qu'un compte rendu. Les faire transiter dans la reponse HTTP les ferait exister a deux
 * endroits — dans la reponse et en base — avec la certitude qu'un jour les deux different.
 */
@Component
public class TopicsClient {

    private static final Logger log = LoggerFactory.getLogger(TopicsClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public TopicsClient(RestTemplateFactory templates, ObjectMapper mapper,
            @Value("${app.ai-service.base-url}") String baseUrl) {
        // Reduction de dimension, clustering, puis un appel de nommage par sujet : plusieurs
        // minutes sur une fenetre chargee. Comme le digest, personne n'attend devant l'ecran —
        // mais un delai doit exister, sinon un service IA bloque immobilise un fil Tomcat.
        this.restTemplate = templates.create(3_000, 300_000);
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Compte rendu d'une detection : ce qui a ete analyse, et combien de sujets en sont sortis. */
    public record Result(int windowDays, int analysed, int topics) {
    }

    @SuppressWarnings("unchecked")
    public Result detect(Integer windowDays) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));

        Map<String, Object> payload = new HashMap<>();
        // `null` demande la fenetre configuree cote service IA. `Map.of` refuse les valeurs nulles.
        payload.put("window_days", windowDays);

        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new AiServiceException(500, "Sujets emergents", "topics", "Demande illisible");
        }

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + "/topics/detect", HttpMethod.POST,
                    new HttpEntity<>(body, headers), Map.class);

            Map<String, Object> map = response.getBody();
            if (map == null) {
                throw new AiServiceException(502, "Sujets emergents", "topics",
                        "Reponse vide du service d'analyse");
            }
            return new Result(
                    asInt(map.get("window_days")),
                    asInt(map.get("analysed")),
                    asInt(map.get("topics")));

        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Detection des sujets impossible: {}", e.getMessage());
            throw new AiServiceException(503, "Sujets emergents", "topics",
                    "Le service d'analyse est momentanement indisponible");
        }
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
