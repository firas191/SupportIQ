package com.supportiq.backend.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.supportiq.backend.tickets.TicketRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * S2-J2 : upload -> confirm avec mapping -> insertion des tickets, sur PostgreSQL reel.
 * Verifie l'insertion (via external_ref), le rejet d'un mapping sans 'subject' (400) et
 * la double confirmation (409).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConfirmImportIntegrationTest {

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
        registry.add("app.imports.storage-dir",
                () -> System.getProperty("java.io.tmpdir") + "/supportiq-test-imports");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    TicketRepository tickets;

    @Test
    void confirm_mapsColumns_andInsertsTickets() {
        String token = adminToken();
        String csv = "ref,email,subject,lang\n"
                + "CONF-A-1,a@x.com,Bonjour,fr\n"
                + "CONF-A-2,b@x.com,Hello,en\n";
        long importId = upload(csv, "a.csv", token);

        Map<String, String> mapping = Map.of(
                "externalRef", "ref",
                "customerEmail", "email",
                "subject", "subject",
                "language", "lang");
        ResponseEntity<Map> resp = confirm(importId, mapping, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("inserted")).isEqualTo(2);
        assertThat(resp.getBody().get("status")).isEqualTo("DONE");
        assertThat(tickets.findExistingExternalRefs(List.of("CONF-A-1", "CONF-A-2"))).hasSize(2);
    }

    @Test
    void confirm_withoutSubjectMapping_isBadRequest() {
        String token = adminToken();
        long importId = upload("ref,subject\nCONF-B-1,Bonjour\n", "b.csv", token);

        ResponseEntity<Map> resp = confirm(importId, Map.of("externalRef", "ref"), token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void confirm_twice_isConflict() {
        String token = adminToken();
        long importId = upload("ref,subject\nCONF-C-1,Bonjour\n", "c.csv", token);
        Map<String, String> mapping = Map.of("externalRef", "ref", "subject", "subject");

        assertThat(confirm(importId, mapping, token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirm(importId, mapping, token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // --- helpers ---------------------------------------------------------------

    private long upload(String csv, String filename, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        ByteArrayResource resource = new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);
        ResponseEntity<Map> resp = rest.postForEntity("/api/imports", new HttpEntity<>(body, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) resp.getBody().get("importId")).longValue();
    }

    private ResponseEntity<Map> confirm(long importId, Map<String, String> mapping, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.postForEntity("/api/imports/" + importId + "/confirm",
                new HttpEntity<>(Map.of("mapping", mapping), headers), Map.class);
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
