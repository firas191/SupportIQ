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
 * S4-J3 : recherche full-text (tsvector FR/EN, index GIN) combinee aux filtres structures.
 * Verifie le stemming FR et EN, la combinaison recherche + filtre, et les filtres d'analyse.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TicketSearchIntegrationTest {

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
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    TicketRepository tickets;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM analyses");
        tickets.deleteAll();

        Long fr = save("S-1", "fr", "Paiement refuse",
                "Ma carte a ete refusee lors du paiement de la commande");
        save("S-2", "fr", "Livraison en retard", "Le colis n'est jamais arrive, je suis mecontent");
        Long en = save("S-3", "en", "Refund request", "I would like a refund for my last payment");
        save("S-4", "en", "Login issue", "I cannot access my account since this morning");

        analyse(fr, "HIGH", "FACTURATION", "NEG");
        analyse(en, "MEDIUM", "FACTURATION", "NEU");
    }

    @Test
    void frenchStemming_findsInflectedForms() {
        // 'paiements' (pluriel) doit trouver 'paiement' grace au stemming francais.
        Map body = search("q=paiements");
        assertThat(((Number) body.get("totalElements")).intValue()).isEqualTo(1);
        assertThat(firstSubject(body)).isEqualTo("Paiement refuse");
    }

    @Test
    void englishStemming_findsInflectedForms() {
        // 'refunds' doit trouver 'refund' via la configuration anglaise.
        Map body = search("q=refunds");
        assertThat(((Number) body.get("totalElements")).intValue()).isEqualTo(1);
        assertThat(firstSubject(body)).isEqualTo("Refund request");
    }

    @Test
    void searchMatchesBodyNotOnlySubject() {
        Map body = search("q=colis");
        assertThat(((Number) body.get("totalElements")).intValue()).isEqualTo(1);
        assertThat(firstSubject(body)).isEqualTo("Livraison en retard");
    }

    @Test
    void search_combinedWithStructuredFilter() {
        // Recherche + filtre langue : seul le ticket FR sur le paiement doit sortir.
        Map body = search("q=paiement&language=fr");
        assertThat(((Number) body.get("totalElements")).intValue()).isEqualTo(1);

        // Le meme mot avec la mauvaise langue ne renvoie rien.
        assertThat(((Number) search("q=paiement&language=en").get("totalElements")).intValue()).isZero();
    }

    @Test
    void filter_byAnalysisFields() {
        assertThat(((Number) search("category=FACTURATION").get("totalElements")).intValue()).isEqualTo(2);
        assertThat(((Number) search("priority=HIGH").get("totalElements")).intValue()).isEqualTo(1);
        assertThat(((Number) search("sentiment=NEG").get("totalElements")).intValue()).isEqualTo(1);
    }

    @Test
    void summary_carriesAnalysisFields() {
        // La vue liste doit *retourner* la priorite, la categorie et l'humeur, pas seulement
        // permettre de filtrer dessus : sans cela l'interface ne peut pas les afficher.
        Map analysed = search("q=paiement&language=fr");
        Map<String, Object> row = firstRow(analysed);
        assertThat(row.get("priority")).isEqualTo("HIGH");
        assertThat(row.get("category")).isEqualTo("FACTURATION");
        assertThat(row.get("sentiment")).isEqualTo("NEG");
    }

    @Test
    void summary_analysisFieldsAreNullWhenNotAnalysed() {
        // Jointure externe : un ticket pas encore analyse sort quand meme, champs a null.
        Map body = search("q=colis");
        Map<String, Object> row = firstRow(body);
        assertThat(row.get("subject")).isEqualTo("Livraison en retard");
        assertThat(row.get("priority")).isNull();
        assertThat(row.get("category")).isNull();
        assertThat(row.get("sentiment")).isNull();
    }

    @Test
    void invalidAnalysisFilter_isBadRequest() {
        ResponseEntity<Map> resp = get("/api/tickets?category=BOGUS", adminToken());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void noQuery_returnsAllPaged() {
        Map body = search("size=2");
        assertThat(((Number) body.get("totalElements")).intValue()).isEqualTo(4);
        assertThat((List<?>) body.get("content")).hasSize(2);
    }

    // --- helpers ---------------------------------------------------------------

    private Long save(String ref, String lang, String subject, String body) {
        return tickets.save(Ticket.builder()
                .externalRef(ref).source(TicketSource.FILE).status(TicketStatus.NEW)
                .subject(subject).body(body).language(lang)
                .build()).getId();
    }

    private void analyse(Long ticketId, String priority, String category, String sentiment) {
        jdbc.update("""
                INSERT INTO analyses (ticket_id, priority, category, sentiment, keywords,
                                      confidence, model_used, escalated_to_llm)
                VALUES (?, ?, ?, ?, '{}', 0.9, 'test', false)
                """, ticketId, priority, category, sentiment);
    }

    private Map search(String query) {
        ResponseEntity<Map> resp = get("/api/tickets?" + query, adminToken());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstRow(Map body) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        return content.get(0);
    }

    private String firstSubject(Map body) {
        return (String) firstRow(body).get("subject");
    }

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
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
