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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * S2-J4 : liste des tickets sur PostgreSQL reel. Verifie pagination, filtres (status/source/q),
 * rejet d'un filtre invalide (400) et l'exigence d'authentification (401 sans jeton).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TicketListIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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

    @BeforeEach
    void seed() {
        tickets.deleteAll();
        tickets.save(ticket("L-1", TicketSource.FILE, TicketStatus.NEW, "Paiement refuse", "fr"));
        tickets.save(ticket("L-2", TicketSource.WEBHOOK, TicketStatus.NEW, "Login issue", "en"));
        tickets.save(ticket("L-3", TicketSource.FILE, TicketStatus.RESOLVED, "Paiement en double", "fr"));
    }

    @Test
    void list_returnsPagedResults() {
        ResponseEntity<Map> resp = get("/api/tickets?size=2&page=0", adminToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) resp.getBody().get("content")).hasSize(2);
        assertThat(((Number) resp.getBody().get("totalElements")).intValue()).isEqualTo(3);
        assertThat(((Number) resp.getBody().get("totalPages")).intValue()).isEqualTo(2);
        assertThat(resp.getBody().get("last")).isEqualTo(false);
    }

    @Test
    void filter_byStatus() {
        ResponseEntity<Map> resp = get("/api/tickets?status=NEW", adminToken());
        assertThat(((Number) resp.getBody().get("totalElements")).intValue()).isEqualTo(2);
    }

    @Test
    void filter_bySource() {
        ResponseEntity<Map> resp = get("/api/tickets?source=WEBHOOK", adminToken());
        assertThat(((Number) resp.getBody().get("totalElements")).intValue()).isEqualTo(1);
    }

    @Test
    void search_byQuery_isCaseInsensitive() {
        ResponseEntity<Map> resp = get("/api/tickets?q=paiement", adminToken());
        assertThat(((Number) resp.getBody().get("totalElements")).intValue()).isEqualTo(2);
    }

    @Test
    void invalidStatusFilter_isBadRequest() {
        ResponseEntity<Map> resp = get("/api/tickets?status=BOGUS", adminToken());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void withoutToken_isUnauthorized() {
        ResponseEntity<Map> resp = rest.getForEntity("/api/tickets", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers ---------------------------------------------------------------

    private static Ticket ticket(String ref, TicketSource source, TicketStatus status, String subject, String lang) {
        return Ticket.builder()
                .externalRef(ref).source(source).status(status)
                .subject(subject).body("corps de " + subject).language(lang)
                .build();
    }

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
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
