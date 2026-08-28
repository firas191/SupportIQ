package com.supportiq.backend.digest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportiq.backend.common.http.RestTemplateFactory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
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
import org.springframework.web.client.RestTemplate;

/**
 * Demande la synthese hebdomadaire au service IA (S6-J4).
 *
 * <p>Quatrieme client HTTP du projet, et le premier ecrit apres le defaut du S6-J3 : fabrique de
 * requetes posee <b>explicitement</b> (les deux clients construits par {@code RestTemplateBuilder}
 * envoyaient un corps vide) et charset UTF-8 explicite. Ces deux lignes ne sont pas de la
 * precaution abstraite — chacune corrige un bug qui a reellement coute une soiree.
 */
@Component
public class DigestClient {

    private static final Logger log = LoggerFactory.getLogger(DigestClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public DigestClient(RestTemplateFactory templates, ObjectMapper mapper,
            @Value("${app.ai-service.base-url}") String baseUrl) {
        // Collecte des agregats, redaction du commentaire, puis rendu PDF : le plus lourd des
        // appels de la plateforme, et le seul dont personne n'attend le resultat a l'ecran.
        this.restTemplate = templates.create(3_000, 180_000);
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Resultat brut : Markdown, chiffres, et PDF deja decode (ou {@code null}). */
    public record Report(LocalDate weekStart, String markdown, String statsJson, byte[] pdf) {
    }

    @SuppressWarnings("unchecked")
    public Report generate(LocalDate weekStart) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));

        Map<String, Object> payload = new HashMap<>();
        // `null` est une valeur significative ici : elle demande « la semaine ecoulee ».
        // `Map.of` refuse les valeurs nulles, d'ou le HashMap.
        payload.put("week_start", weekStart == null ? null : weekStart.toString());

        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new DigestException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Demande illisible");
        }

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + "/agents/digest", HttpMethod.POST,
                    new HttpEntity<>(body, headers), Map.class);

            Map<String, Object> map = response.getBody();
            if (map == null) {
                throw new DigestException(HttpStatus.BAD_GATEWAY.value(),
                        "Reponse vide du service d'analyse");
            }

            Object stats = map.getOrDefault("stats", Map.of());
            String pdfBase64 = (String) map.get("pdf_base64");
            return new Report(
                    LocalDate.parse(String.valueOf(map.get("week_start"))),
                    String.valueOf(map.getOrDefault("markdown", "")),
                    mapper.writeValueAsString(stats),
                    pdfBase64 == null ? null : Base64.getDecoder().decode(pdfBase64));

        } catch (DigestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Generation du digest impossible: {}", e.getMessage());
            throw new DigestException(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Le service d'analyse est momentanement indisponible");
        }
    }
}
