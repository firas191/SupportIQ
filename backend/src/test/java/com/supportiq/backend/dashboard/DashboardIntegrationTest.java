package com.supportiq.backend.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.supportiq.backend.tickets.Ticket;
import com.supportiq.backend.tickets.TicketRepository;
import com.supportiq.backend.tickets.TicketSource;
import com.supportiq.backend.tickets.TicketStatus;
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
 * S4-J1 : endpoints du dashboard sur PostgreSQL reel (vues V5). Verifie les KPIs calcules,
 * les tendances, et le RBAC (AGENT interdit, MANAGER autorise).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DashboardIntegrationTest {

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

        Long t1 = save("D-1", TicketStatus.NEW, "Paiement refuse");
        Long t2 = save("D-2", TicketStatus.NEW, "Login impossible");
        Long t3 = save("D-3", TicketStatus.RESOLVED, "Question livraison");
        // 3 tickets, 2 analyses : 1 HIGH/NEG escaladee, 1 LOW/POS locale.
        analyse(t1, "HIGH", "FACTURATION", "NEG", true, 0.90);
        analyse(t2, "LOW", "COMPTE", "POS", false, 0.80);
    }

    @Test
    void kpis_areComputedFromViews() {
        ResponseEntity<Map> resp = get("/api/dashboard/kpis", adminToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map body = resp.getBody();
        assertThat(((Number) body.get("totalTickets")).intValue()).isEqualTo(3);
        assertThat(((Number) body.get("newTickets")).intValue()).isEqualTo(2);
        assertThat(((Number) body.get("resolvedTickets")).intValue()).isEqualTo(1);
        assertThat(((Number) body.get("analyzedTickets")).intValue()).isEqualTo(2);
        assertThat(((Number) body.get("highPriority")).intValue()).isEqualTo(1);
        assertThat(((Number) body.get("escalatedToLlm")).intValue()).isEqualTo(1);
        // 1 escalade sur 2 analyses = 50 %
        assertThat(((Number) body.get("escalationRate")).doubleValue()).isEqualTo(50.0);
    }

    @Test
    void trends_returnAllSeries() {
        ResponseEntity<Map> resp = get("/api/dashboard/trends?days=30", adminToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map body = resp.getBody();
        assertThat((List<?>) body.get("daily")).isNotEmpty();
        assertThat((List<?>) body.get("byCategory")).hasSize(2);      // FACTURATION + COMPTE
        assertThat((List<?>) body.get("bySentiment")).hasSize(2);     // NEG + POS
        assertThat((List<?>) body.get("hourly")).isNotEmpty();
    }

    @Test
    void alerts_areEmptyUntilWeek7() {
        ResponseEntity<List> resp = rest.exchange("/api/dashboard/alerts", HttpMethod.GET,
                new HttpEntity<>(bearer(adminToken())), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    @Test
    void agent_isForbidden() {
        String agentToken = createUserAndLogin("agent@supportiq.local", "agent1234", "AGENT");
        ResponseEntity<Map> resp = get("/api/dashboard/kpis", agentToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void withoutToken_isUnauthorized() {
        ResponseEntity<Map> resp = rest.getForEntity("/api/dashboard/kpis", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers ---------------------------------------------------------------

    private Long save(String ref, TicketStatus status, String subject) {
        return tickets.save(Ticket.builder()
                .externalRef(ref).source(TicketSource.FILE).status(status)
                .subject(subject).body("corps de " + subject).language("fr")
                .build()).getId();
    }

    private void analyse(Long ticketId, String priority, String category, String sentiment,
            boolean escalated, double confidence) {
        jdbc.update("""
                INSERT INTO analyses (ticket_id, priority, category, sentiment, keywords,
                                      confidence, model_used, escalated_to_llm)
                VALUES (?, ?, ?, ?, '{}', ?, 'test', ?)
                """, ticketId, priority, category, sentiment, confidence, escalated);
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String adminToken() {
        return login("admin@supportiq.local", "admin1234");
    }

    private String createUserAndLogin(String email, String password, String role) {
        HttpHeaders headers = bearer(adminToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.postForEntity("/api/auth/register",
                new HttpEntity<>(Map.of("email", email, "password", password,
                        "fullName", "Test " + role, "role", role), headers),
                Map.class);
        return login(email, password);
    }

    private String login(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("email", email, "password", password), headers), Map.class);
        return (String) resp.getBody().get("accessToken");
    }
}
