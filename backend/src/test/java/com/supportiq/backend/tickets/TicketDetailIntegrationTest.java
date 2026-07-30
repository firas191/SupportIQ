package com.supportiq.backend.tickets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * S4-J4 : fiche ticket + boucle human-in-the-loop. Verifie le detail (ticket + analyse), la
 * correction (trace dans `annotations` ET analyse mise a jour), la fusion de doublons et les
 * garde-fous (404, auto-fusion, double fusion, ticket non analyse).
 *
 * <p>Le service IA n'est pas requis : le client de similarite degrade en liste vide (base-url pointe
 * vers un port ferme).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TicketDetailIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.security.jwt.secret",
                () -> "test-secret-supportiq-0123456789-abcdefghijklmnop");
        registry.add("app.bootstrap.admin.email", () -> "admin@supportiq.local");
        registry.add("app.bootstrap.admin.password", () -> "admin1234");
        // Service IA volontairement injoignable : la fiche doit rester fonctionnelle.
        registry.add("app.ai-service.base-url", () -> "http://localhost:1");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    TicketRepository tickets;

    @Autowired
    JdbcTemplate jdbc;

    private Long analysed;
    private Long other;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM annotations");
        jdbc.update("DELETE FROM analyses");
        jdbc.update("UPDATE tickets SET merged_into_id = NULL");
        tickets.deleteAll();

        analysed = save("D-1", "Paiement refuse", "Ma carte a ete refusee");
        other = save("D-2", "Doublon paiement", "Carte refusee aussi");
        jdbc.update("""
                INSERT INTO analyses (ticket_id, priority, category, sentiment, keywords,
                                      confidence, model_used, escalated_to_llm)
                VALUES (?, 'MEDIUM', 'TECHNIQUE', 'NEU', '{carte,paiement}', 0.62, 'xlm-r-onnx', false)
                """, analysed);
    }

    @Test
    @SuppressWarnings("unchecked")
    void detail_returnsTicketWithAnalysis() {
        Map body = get("/api/tickets/" + analysed);
        assertThat(body.get("subject")).isEqualTo("Paiement refuse");

        Map analysis = (Map) body.get("analysis");
        assertThat(analysis.get("category")).isEqualTo("TECHNIQUE");
        assertThat(analysis.get("modelUsed")).isEqualTo("xlm-r-onnx");
        List<String> keywords = (List<String>) analysis.get("keywords");
        assertThat(keywords).contains("carte", "paiement");
        // Service IA injoignable -> pas de similaires, mais la fiche repond quand meme.
        assertThat((List<?>) body.get("similar")).isEmpty();
    }

    @Test
    void detail_unknownTicket_isNotFound() {
        ResponseEntity<Map> resp = rest.exchange("/api/tickets/999999", HttpMethod.GET,
                new HttpEntity<>(bearer()), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void annotation_tracesCorrectionAndUpdatesAnalysis() {
        Map body = post("/api/tickets/" + analysed + "/annotations",
                Map.of("field", "category", "value", "FACTURATION"));

        // 1. L'analyse renvoyee est corrigee...
        assertThat(((Map) body.get("analysis")).get("category")).isEqualTo("FACTURATION");
        // 2. ...et la correction est tracee avec l'ancienne valeur predite.
        Map<String, Object> annotation = jdbc.queryForMap(
                "SELECT field, predicted, corrected FROM annotations WHERE ticket_id = ?", analysed);
        assertThat(annotation.get("field")).isEqualTo("category");
        assertThat(annotation.get("predicted")).isEqualTo("TECHNIQUE");
        assertThat(annotation.get("corrected")).isEqualTo("FACTURATION");
    }

    @Test
    void annotation_invalidValue_isBadRequest() {
        ResponseEntity<Map> resp = postRaw("/api/tickets/" + analysed + "/annotations",
                Map.of("field", "category", "value", "BOGUS"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void annotation_onUnanalysedTicket_isConflict() {
        ResponseEntity<Map> resp = postRaw("/api/tickets/" + other + "/annotations",
                Map.of("field", "priority", "value", "HIGH"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void merge_marksDuplicateAsMerged() {
        Map body = post("/api/tickets/" + other + "/merge", Map.of("targetId", analysed));
        assertThat(body.get("status")).isEqualTo("MERGED");
        assertThat(((Number) body.get("mergedIntoId")).longValue()).isEqualTo(analysed);
    }

    @Test
    void merge_intoItself_isConflict() {
        ResponseEntity<Map> resp = postRaw("/api/tickets/" + analysed + "/merge",
                Map.of("targetId", analysed));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void merge_twice_isConflict() {
        post("/api/tickets/" + other + "/merge", Map.of("targetId", analysed));
        ResponseEntity<Map> resp = postRaw("/api/tickets/" + other + "/merge",
                Map.of("targetId", analysed));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // --- helpers ---------------------------------------------------------------

    private Long save(String ref, String subject, String body) {
        return tickets.save(Ticket.builder()
                .externalRef(ref).source(TicketSource.FILE).status(TicketStatus.NEW)
                .subject(subject).body(body).language("fr")
                .build()).getId();
    }

    private Map get(String path) {
        ResponseEntity<Map> resp = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(bearer()), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private Map post(String path, Map<String, Object> payload) {
        ResponseEntity<Map> resp = postRaw(path, payload);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private ResponseEntity<Map> postRaw(String path, Map<String, Object> payload) {
        HttpHeaders headers = bearer();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(payload, headers), Map.class);
    }

    private HttpHeaders bearer() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken());
        return headers;
    }

    private String adminToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("email", "admin@supportiq.local", "password", "admin1234"), headers),
                Map.class);
        return (String) resp.getBody().get("accessToken");
    }
}
