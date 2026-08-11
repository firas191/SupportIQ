package com.supportiq.backend.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Client de l'agent Insight (S6-J3). Spring transporte et applique le RBAC ; tout le reste — la
 * traduction en SQL, les deux barrieres de securite, l'execution — vit dans le service IA.
 *
 * <p><b>Delais.</b> Une question peut couter jusqu'a trois generations SQL plus une synthese, soit
 * quatre appels de modele. Sans expiration, un service IA bloque immobiliserait un fil Tomcat par
 * question posee. Meme raisonnement que pour {@code DraftClient}, avec une lecture un peu plus
 * courte : un manager attend devant son ecran, il n'attendra pas deux minutes.
 */
@Component
public class InsightClient {

    private static final Logger log = LoggerFactory.getLogger(InsightClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public InsightClient(ObjectMapper mapper, @Value("${app.ai-service.base-url}") String baseUrl) {
        // `new RestTemplate(factory)` et **non** `RestTemplateBuilder`.
        //
        // Constate en S6-J3 : les deux clients construits par le builder (celui-ci et
        // `DraftClient`) envoyaient un corps **vide** — FastAPI repondait 422 « Field required »
        // sans jamais voir la question. Les deux clients qui fonctionnent depuis des semaines
        // (`KbClient`, `SimilarTicketClient`) utilisent `new RestTemplate()`.
        //
        // Le builder choisit sa fabrique de requetes selon les bibliotheques presentes au demarrage ;
        // le comportement observe ici differe de celui de la fabrique par defaut. Plutot que de
        // dependre de cette detection, on pose explicitement la fabrique — et on garde les delais
        // d'expiration, qui etaient la seule raison d'utiliser le builder.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        // Une question coute jusqu'a quatre appels de modele. Sans expiration, un service IA bloque
        // immobiliserait un fil Tomcat par question posee.
        factory.setReadTimeout(90_000);

        this.restTemplate = new RestTemplate(factory);
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @SuppressWarnings("unchecked")
    public InsightAnswer ask(String question, String userRole) {
        HttpHeaders headers = new HttpHeaders();
        // **Charset explicite** — c'est le correctif du defaut trouve en S6-J3. Un
        // `HttpEntity<String>` avec un Content-Type sans charset est ecrit par
        // `StringHttpMessageConverter` en ISO-8859-1. Tant que le texte est en ASCII pur les deux
        // encodages coincident, ce qui masque le probleme ; des qu'une question porte un accent,
        // les octets ne sont plus de l'UTF-8 valide et FastAPI refuse le corps en 422 *avant*
        // d'avoir lu la question.
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));

        // Corps en **String** et non en `byte[]` : avec les convertisseurs par defaut de ce
        // projet, un corps binaire part vide (constate en S6-J3, et deja en S4-J4 avec
        // `RestClient` + Map). Le motif qui fonctionne ici est celui des autres clients — une
        // chaine JSON — et il n'y a aucune raison de s'en ecarter.
        //
        // La chaine est produite par Jackson plutot qu'assemblee a la main : l'echappement JOSN
        // ecrit a la main etait la seconde faiblesse de cette methode.
        String payload;
        try {
            payload = mapper.writeValueAsString(Map.of(
                    "question", question,
                    "user_role", userRole == null ? "" : userRole));
        } catch (Exception e) {
            throw new InsightException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "Question illisible");
        }

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + "/agents/insight", HttpMethod.POST,
                    new HttpEntity<>(payload, headers), Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw new InsightException(HttpStatus.BAD_GATEWAY.value(),
                        "Reponse vide du service d'analyse");
            }
            return toAnswer(body);

        } catch (HttpStatusCodeException e) {
            throw translate(e);
        } catch (InsightException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Service d'analyse injoignable: {}", e.getMessage());
            throw new InsightException(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "L'assistant d'analyse est momentanement indisponible");
        }
    }

    @SuppressWarnings("unchecked")
    private static InsightAnswer toAnswer(Map<String, Object> body) {
        Map<String, Object> chart = (Map<String, Object>) body.getOrDefault("chart", Map.of());
        List<Object> rawRows = (List<Object>) body.getOrDefault("rows", List.of());

        List<List<Object>> rows = new ArrayList<>();
        for (Object row : rawRows) {
            rows.add(row instanceof List<?> list ? new ArrayList<>(list) : List.of(row));
        }

        return new InsightAnswer(
                str(body.get("question")),
                str(body.get("sql")),
                str(body.getOrDefault("answer", "")),
                new InsightAnswer.Chart(
                        str(chart.getOrDefault("type", "none")),
                        str(chart.get("x")),
                        str(chart.get("y")),
                        str(chart.getOrDefault("reason", ""))),
                (List<String>) body.getOrDefault("columns", List.of()),
                rows,
                body.get("row_count") instanceof Number n ? n.intValue() : rows.size(),
                Boolean.TRUE.equals(body.get("truncated")));
    }

    /**
     * Conserve la distinction « question hors perimetre » / « panne ».
     *
     * <p>Le service IA repond 422 quand la question ne peut pas etre traduite — donnee absente des
     * vues, requete irreparable apres trois essais. Ce n'est pas une erreur du systeme : c'est un
     * refus, et l'interface doit le presenter comme tel. L'aplatir en 500 ferait croire a une panne.
     */
    private InsightException translate(HttpStatusCodeException e) {
        int status = e.getStatusCode().value();
        String detail = upstreamDetail(e);

        // Le corps de la reponse amont est journalise en entier. Sans lui, « 422 » ne distingue pas
        // « la question sort du perimetre » d'une requete malformee de notre cote — deux causes qui
        // demandent des corrections opposees, et qu'un message generique rend indiscernables.
        log.info("Service d'analyse: {} sur une question Insight — {}", status, detail);

        if (status == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
            // On **transmet** le message amont plutot que d'en substituer un : le service IA
            // distingue « hors perimetre » de « requete irreparable apres trois essais », et
            // ecraser cette nuance ici la perdrait pour tout le monde, utilisateur compris.
            return new InsightException(status, detail != null && !detail.isBlank()
                    ? detail
                    : "Cette question ne peut pas etre repondue avec les donnees disponibles.");
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return new InsightException(status,
                    "L'assistant d'analyse est momentanement indisponible");
        }
        return new InsightException(HttpStatus.BAD_GATEWAY.value(),
                "Le service d'analyse a refuse la demande");
    }

    /** Champ {@code detail} de la reponse FastAPI, ou le corps brut s'il n'a pas cette forme. */
    private static String upstreamDetail(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return null;
        }
        // `detail` peut etre une chaine (nos refus applicatifs) **ou un tableau d'objets** (erreurs
        // de validation Pydantic). Extraire naivement la premiere chaine venue donnait « type » —
        // la premiere cle d'un objet d'erreur — ce qui n'apprenait rien. On ne tente donc
        // l'extraction que sur la forme chaine, et on rend le corps entier sinon.
        int start = body.indexOf("\"detail\"");
        int colon = start < 0 ? -1 : body.indexOf(':', start);
        boolean isStringDetail = colon > 0 && body.substring(colon + 1).stripLeading().startsWith("\"");
        if (!isStringDetail) {
            return body.length() > 400 ? body.substring(0, 400) : body;
        }
        int open = body.indexOf('"', colon + 1);
        int close = body.indexOf('"', open + 1);
        return close < 0 ? body : body.substring(open + 1, close);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
