package com.supportiq.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests d'integration auth sur PostgreSQL reel (Testcontainers).
 * Couvre : login de l'admin seede, RBAC sur /register (401 sans token, 403 en AGENT, 201 en ADMIN),
 * rotation du refresh (ancien invalide apres usage) et revocation au logout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthIntegrationTest {

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

    @Test
    void seededAdmin_canLogin_andReceivesTokens() {
        ResponseEntity<Map> resp = login("admin@supportiq.local", "admin1234");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys("accessToken", "refreshToken");
        assertThat(resp.getBody().get("tokenType")).isEqualTo("Bearer");
    }

    @Test
    void register_withoutToken_isUnauthorized() {
        ResponseEntity<Map> resp = rest.postForEntity("/api/auth/register",
                new HttpEntity<>(newUserBody("nobody@x.com", "AGENT"), jsonHeaders()), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void admin_canRegisterUser_andThatUserCanLogin() {
        String adminToken = accessToken("admin@supportiq.local", "admin1234");

        ResponseEntity<Map> created = rest.exchange("/api/auth/register", HttpMethod.POST,
                new HttpEntity<>(newUserBody("agent1@x.com", "AGENT"), bearer(adminToken)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("role")).isEqualTo("AGENT");

        assertThat(login("agent1@x.com", "password123").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void agent_cannotRegister_isForbidden() {
        String adminToken = accessToken("admin@supportiq.local", "admin1234");
        rest.exchange("/api/auth/register", HttpMethod.POST,
                new HttpEntity<>(newUserBody("agent2@x.com", "AGENT"), bearer(adminToken)), Map.class);

        String agentToken = accessToken("agent2@x.com", "password123");
        ResponseEntity<Map> resp = rest.exchange("/api/auth/register", HttpMethod.POST,
                new HttpEntity<>(newUserBody("intrus@x.com", "ADMIN"), bearer(agentToken)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void refresh_rotates_andOldTokenIsRejected() {
        ResponseEntity<Map> loginResp = login("admin@supportiq.local", "admin1234");
        String oldRefresh = (String) loginResp.getBody().get("refreshToken");

        ResponseEntity<Map> refreshed = rest.postForEntity("/api/auth/refresh",
                new HttpEntity<>(Map.of("refreshToken", oldRefresh), jsonHeaders()), Map.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody().get("refreshToken")).isNotEqualTo(oldRefresh);

        // Rejeu de l'ancien refresh -> refuse (rotation).
        ResponseEntity<Map> replay = rest.postForEntity("/api/auth/refresh",
                new HttpEntity<>(Map.of("refreshToken", oldRefresh), jsonHeaders()), Map.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logout_revokesRefreshToken() {
        ResponseEntity<Map> loginResp = login("admin@supportiq.local", "admin1234");
        String refresh = (String) loginResp.getBody().get("refreshToken");

        ResponseEntity<Void> logout = rest.postForEntity("/api/auth/logout",
                new HttpEntity<>(Map.of("refreshToken", refresh), jsonHeaders()), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> afterLogout = rest.postForEntity("/api/auth/refresh",
                new HttpEntity<>(Map.of("refreshToken", refresh), jsonHeaders()), Map.class);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void me_returnsRoleOfCaller() {
        String adminToken = accessToken("admin@supportiq.local", "admin1234");
        ResponseEntity<Map> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer(adminToken)), Map.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("role")).isEqualTo("ADMIN");
    }

    // --- helpers ---------------------------------------------------------------

    private ResponseEntity<Map> login(String email, String password) {
        return rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("email", email, "password", password), jsonHeaders()), Map.class);
    }

    private String accessToken(String email, String password) {
        return (String) login(email, password).getBody().get("accessToken");
    }

    private Map<String, Object> newUserBody(String email, String role) {
        return Map.of("email", email, "password", "password123", "fullName", "Test User", "role", role);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
