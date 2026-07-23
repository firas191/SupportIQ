package com.supportiq.backend.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.supportiq.backend.tickets.TicketRepository;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * S2-J4 : webhook d'ingestion sur PostgreSQL reel. Verifie la creation (202 + ticket source WEBHOOK),
 * l'idempotence par external_ref (200 DUPLICATE), et les refus d'auth (cle API / signature -> 401).
 * RabbitMQ n'est pas requis : la publication apres commit est tolerante a un broker absent (log).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WebhookIntegrationTest {

    private static final String API_KEY = "test-key";
    private static final String SECRET = "test-secret";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.security.jwt.secret",
                () -> "test-secret-supportiq-0123456789-abcdefghijklmnop");
        registry.add("app.webhook.api-key", () -> API_KEY);
        registry.add("app.webhook.secret", () -> SECRET);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    TicketRepository tickets;

    @Test
    void validSignedRequest_creates202AndTicket() {
        String body = "{\"externalRef\":\"WH-1\",\"customerEmail\":\"a@x.com\","
                + "\"subject\":\"Paiement echoue\",\"body\":\"Ma carte est refusee\",\"language\":\"fr\"}";
        ResponseEntity<Map> resp = post(body, API_KEY, sign(body));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody().get("result")).isEqualTo("ACCEPTED");
        assertThat(tickets.findIdByExternalRef("WH-1")).isPresent();
    }

    @Test
    void duplicateExternalRef_returns200Duplicate() {
        String body = "{\"externalRef\":\"WH-DUP\",\"subject\":\"Doublon\"}";
        assertThat(post(body, API_KEY, sign(body)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<Map> second = post(body, API_KEY, sign(body));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("result")).isEqualTo("DUPLICATE");
    }

    @Test
    void wrongApiKey_isUnauthorized() {
        String body = "{\"externalRef\":\"WH-BADKEY\",\"subject\":\"x\"}";
        ResponseEntity<Map> resp = post(body, "intrus", sign(body));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(tickets.findIdByExternalRef("WH-BADKEY")).isEmpty();
    }

    @Test
    void wrongSignature_isUnauthorized() {
        String body = "{\"externalRef\":\"WH-BADSIG\",\"subject\":\"x\"}";
        ResponseEntity<Map> resp = post(body, API_KEY, "sha256=deadbeef");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(tickets.findIdByExternalRef("WH-BADSIG")).isEmpty();
    }

    @Test
    void missingSubject_isBadRequest() {
        String body = "{\"externalRef\":\"WH-NOSUBJ\",\"body\":\"corps sans sujet\"}";
        ResponseEntity<Map> resp = post(body, API_KEY, sign(body));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- helpers ---------------------------------------------------------------

    private ResponseEntity<Map> post(String body, String apiKey, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        headers.set("X-Api-Key", apiKey);
        headers.set("X-Signature", signature);
        return rest.postForEntity("/api/webhooks/tickets", new HttpEntity<>(body, headers), Map.class);
    }

    private static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
