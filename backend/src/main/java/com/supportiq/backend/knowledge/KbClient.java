package com.supportiq.backend.knowledge;

import com.supportiq.backend.common.http.RestTemplateFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Client du service IA pour la base de connaissances (S5-J1).
 *
 * <p>Tout ce qui demande un **modele** part ici : decoupage semantique, embeddings, recherche
 * vectorielle. Spring ne fait que transporter et appliquer le RBAC.
 *
 * <p>Contrairement a {@link com.supportiq.backend.tickets.SimilarTicketClient}, les echecs ne sont
 * <b>pas</b> avales. La difference est intentionnelle : la liste des tickets similaires est un
 * enrichissement (la fiche reste utile sans), alors qu'un import de document qui echoue en silence
 * laisserait l'administrateur croire que sa FAQ est indexee. On leve donc, avec un statut parlant.
 *
 * <p>{@code RestTemplate} et non {@code RestClient} : meme choix qu'en S4-J4, ou {@code RestClient}
 * partait avec un corps vide sur les requetes construites a partir d'une Map.
 */
@Component
public class KbClient {

    private static final Logger log = LoggerFactory.getLogger(KbClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public KbClient(RestTemplateFactory templates,
            @Value("${app.ai-service.base-url}") String baseUrl) {
        // Comme `SimilarTicketClient`, ce client attendait indefiniment. 120 s de lecture : le
        // premier import de document declenche le telechargement du modele d'embeddings (~1 Go),
        // et une recherche hybride reconstruit l'index BM25 en memoire.
        this.restTemplate = templates.create(3_000, 120_000);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @SuppressWarnings("unchecked")
    public KbIngestResponse ingest(String filename, byte[] content) {
        // Le nom de fichier doit traverser le multipart : c'est la cle d'idempotence cote IA
        // (ré-importer le meme nom remplace les fragments au lieu de les dupliquer).
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        Map<String, Object> res = post("/kb/documents", new HttpEntity<>(body, headers), Map.class);
        return new KbIngestResponse(
                str(res.get("source")),
                str(res.get("title")),
                intOf(res.get("chunks")),
                intOf(res.get("indexed")),
                intOf(res.get("characters")));
    }

    @SuppressWarnings("unchecked")
    public List<KbChunkResponse> search(String question, int k, String mode) {
        HttpHeaders headers = new HttpHeaders();
        // **Charset explicite.** Un `HttpEntity<String>` avec un Content-Type sans charset est
        // ecrit par Spring en ISO-8859-1. Tant que la question est en ASCII pur les deux encodages
        // coincident, ce qui masque le defaut ; des qu'elle contient un accent (« delai »
        // -> « delai »), les octets ne sont plus de l'UTF-8 valide et FastAPI refuse le corps en
        // 422 avant meme de lire la question. Diagnostique en S6-J3 sur le client Insight.
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        String payload = "{\"question\":" + jsonString(question)
                + ",\"k\":" + k
                + ",\"mode\":" + jsonString(mode) + "}";

        List<Map<String, Object>> rows =
                post("/kb/search", new HttpEntity<>(payload, headers), List.class);

        List<KbChunkResponse> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new KbChunkResponse(
                    ((Number) r.get("id")).longValue(),
                    str(r.get("title")),
                    str(r.get("source")),
                    intOf(r.get("chunk_index")),
                    str(r.get("heading")),
                    str(r.get("content")),
                    r.get("similarity") == null ? 0d : ((Number) r.get("similarity")).doubleValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public int reindex(boolean force) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> res = post(
                "/kb/reindex?force=" + force, new HttpEntity<>("", headers), Map.class);
        return intOf(res.get("processed"));
    }

    @SuppressWarnings("unchecked")
    public int delete(String source) {
        try {
            ResponseEntity<Map> res = restTemplate.exchange(
                    baseUrl + "/kb/documents/" + source, HttpMethod.DELETE, null, Map.class);
            Object deleted = res.getBody() == null ? null : res.getBody().get("deleted");
            return intOf(deleted);
        } catch (HttpStatusCodeException e) {
            throw translate(e);
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    private <T> T post(String path, HttpEntity<?> entity, Class<T> type) {
        try {
            ResponseEntity<T> response =
                    restTemplate.exchange(baseUrl + path, HttpMethod.POST, entity, type);
            T body = response.getBody();
            if (body == null) {
                throw new KbException(HttpStatus.BAD_GATEWAY.value(), "Reponse vide du service d'analyse");
            }
            return body;
        } catch (HttpStatusCodeException e) {
            throw translate(e);
        } catch (KbException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    /** Conserve la distinction « format refuse » / « panne » plutot que de tout aplatir en 500. */
    private KbException translate(HttpStatusCodeException e) {
        int status = e.getStatusCode().value();
        log.warn("Service d'analyse: {} sur la base de connaissances", status);
        if (status == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) {
            return new KbException(status, "Format de document non pris en charge");
        }
        return new KbException(HttpStatus.BAD_GATEWAY.value(), "Le service d'analyse a refuse la demande");
    }

    private KbException unavailable(Exception e) {
        log.warn("Service d'analyse injoignable: {}", e.getMessage());
        return new KbException(
                HttpStatus.SERVICE_UNAVAILABLE.value(), "Le service d'analyse est momentanement indisponible");
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\"";
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
