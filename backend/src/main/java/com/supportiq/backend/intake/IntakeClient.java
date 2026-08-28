package com.supportiq.backend.intake;

import com.supportiq.backend.common.error.AiServiceException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Envoie un document au service IA pour extraction et structuration (S7-J4).
 *
 * <p>Huitieme client HTTP. Comme {@code KbClient} (S5-J1), il **ne degrade pas en silence** : un
 * document dont l'extraction echoue doit le dire. Rendre un lot vide laisserait croire que le PDF
 * ne contenait aucune demande, ce qui est le pire retour possible — l'utilisateur recommencerait
 * avec un autre fichier au lieu de signaler une panne.
 */
@Component
public class IntakeClient {

    private static final Logger log = LoggerFactory.getLogger(IntakeClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public IntakeClient(@Value("${app.ai-service.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        // Extraction (eventuellement OCR, quelques secondes par page) puis un appel de modele par
        // tranche de 8 000 caracteres. Sur un PDF de trente pages scannees, c'est long.
        factory.setReadTimeout(300_000);
        this.restTemplate = new RestTemplate(factory);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @SuppressWarnings("unchecked")
    public IntakeModels.ExtractionResult extract(String filename, byte[] content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                // Sans nom de fichier, le multipart part sans extension et le service IA ne peut
                // plus choisir son extracteur. Le detail est invisible jusqu'au premier upload.
                return filename;
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + "/extract", HttpMethod.POST,
                    new HttpEntity<>(form, headers), Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw new AiServiceException(502, "Ingestion documentaire", "intake",
                        "Reponse vide du service d'analyse");
            }

            List<Map<String, Object>> raw =
                    (List<Map<String, Object>>) body.getOrDefault("tickets", List.of());
            return new IntakeModels.ExtractionResult(
                    raw.stream().map(IntakeClient::toProposed).toList(),
                    asInt(body.get("pages")),
                    String.valueOf(body.getOrDefault("method", "native")));

        } catch (HttpStatusCodeException e) {
            // 415 « format non pris en charge » n'est pas une panne : le statut amont est preserve
            // pour que l'interface dise « ce format n'est pas accepte » et non « reessayez plus
            // tard ». Meme nuance qu'au KbClient.
            log.info("Extraction refusee ({}) : {}", e.getStatusCode(), e.getMessage());
            throw new AiServiceException(e.getStatusCode().value(), "Ingestion documentaire",
                    "intake", "Ce document n'a pas pu etre exploite");
        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Extraction impossible: {}", e.getMessage());
            throw new AiServiceException(503, "Ingestion documentaire", "intake",
                    "Le service d'analyse est momentanement indisponible");
        }
    }

    @SuppressWarnings("unchecked")
    private static IntakeModels.ProposedTicket toProposed(Map<String, Object> row) {
        Map<String, Object> confidence =
                (Map<String, Object>) row.getOrDefault("confidence", Map.of());
        return new IntakeModels.ProposedTicket(
                str(row.get("subject")),
                str(row.get("body")),
                str(row.get("customer_email")),
                str(row.get("language")),
                new IntakeModels.FieldConfidence(
                        asDouble(confidence.get("subject")),
                        asDouble(confidence.get("body")),
                        asDouble(confidence.get("customer_email"))));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }
}
