package com.supportiq.backend.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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
import org.springframework.http.HttpStatusCode;
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
 * S7-J2 : cycle de vie des alertes sur PostgreSQL reel (V16).
 *
 * <p>Ce qui est teste est la <b>machine a etats</b>, pas la detection. Detecter demande une serie
 * historique et une decomposition saisonniere : c'est teste cote Python, sur des series construites
 * dont on connait la reponse (`tests/test_anomaly.py`). Meme partage qu'au S5-J4 pour les
 * brouillons — chaque garantie est verifiee la ou elle peut l'etre de facon deterministe.
 *
 * <p>Les deux proprietes qui comptent ici : une meme anomalie ne produit qu'une alerte, et un
 * acquittement ne se rejoue pas. La premiere evite qu'un detecteur tournant toutes les cinq minutes
 * rende la fonctionnalite insupportable ; la seconde evite que deux responsables croient chacun
 * avoir pris l'incident en charge.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AlertIntegrationTest {

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
        // Sans cela, le detecteur periodique s'inviterait au milieu des assertions et creerait des
        // alertes que le test n'a pas posees.
        registry.add("app.alerts.auto-detect", () -> "false");
        // Port ferme : le test de degradation doit echouer sur une connexion refusee, et non sur un
        // service IA qui tournerait par hasard sur le poste du developpeur.
        registry.add("app.ai-service.base-url", () -> "http://localhost:1");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AlertRepository repository;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM alerts");
        insert("FACTURATION", "2026-08-20 14:00:00+00", "CRITICAL", 41, 6);
        insert("TECHNIQUE", "2026-08-20 15:00:00+00", "WARNING", 22, 9);
    }

    @Test
    void openAlertsAreListedWithTheirFigures() {
        ResponseEntity<List> resp = get("/api/alerts", adminToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> body = resp.getBody();
        assertThat(body).hasSize(2);

        Map<String, Object> first = body.get(0);
        // Le payload jsonb doit traverser JDBC puis Jackson sans se transformer en chaine : sans
        // ces chiffres, l'interface ne pourrait afficher qu'un score, qui ne veut rien dire.
        Map<String, Object> payload = (Map<String, Object>) first.get("payload");
        assertThat(((Number) payload.get("observed")).intValue()).isPositive();
        assertThat(((Number) payload.get("expected")).intValue()).isPositive();
    }

    @Test
    void theSameAnomalyIsNeverRaisedTwice() {
        // Le detecteur tourne toutes les cinq minutes et redecouvre necessairement les pics
        // recents. Sans la contrainte d'unicite (V16), un pic du matin aurait produit une alerte
        // par passage jusqu'a sortir de la fenetre — soit une trentaine de lignes identiques.
        var again = repository.insertIfAbsent("VOLUME_ANOMALY", "CRITICAL", "FACTURATION",
                OffsetDateTime.parse("2026-08-20T14:00:00Z").toInstant(), "{}");

        assertThat(again).isEmpty();
        assertThat(repository.countOpen()).isEqualTo(2);
    }

    @Test
    void acknowledgingRecordsWhoTookIt() {
        long id = firstId();
        ResponseEntity<Map> resp = post("/api/alerts/" + id + "/ack", adminToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("acknowledgedAt")).isNotNull();
        // Le nom, pas seulement l'horodatage : sans lui, deux responsables traiteraient le meme
        // incident chacun de son cote.
        assertThat(resp.getBody().get("acknowledgedByEmail")).isEqualTo("admin@supportiq.local");
    }

    @Test
    void acknowledgingTwiceIsAConflictNotASilentSuccess() {
        long id = firstId();
        post("/api/alerts/" + id + "/ack", adminToken());

        ResponseEntity<Map> second = post("/api/alerts/" + id + "/ack", adminToken());

        // 409 et non 200 : celui qui arrive second doit savoir que quelqu'un d'autre s'en charge,
        // plutot que de croire qu'il vient de le faire.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void acknowledgingAnUnknownAlertIsNotFound() {
        assertThat(post("/api/alerts/999999/ack", adminToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void handledAlertsFallBehindOpenOnes() {
        // Le tri n'est pas chronologique pur : ce que personne n'a vu passe devant, meme si c'est
        // plus ancien. Une alerte acquittee est une alerte dont quelqu'un s'occupe ; la laisser en
        // tete ferait descendre celle que personne n'a encore regardee.
        post("/api/alerts/" + firstId() + "/ack", adminToken());

        ResponseEntity<List> resp = get("/api/alerts", adminToken());
        List<Map<String, Object>> body = resp.getBody();
        assertThat(body.get(0).get("acknowledgedAt")).isNull();
        assertThat(body.get(1).get("acknowledgedAt")).isNotNull();
    }

    @Test
    void openCountIgnoresHandledAlerts() {
        post("/api/alerts/" + firstId() + "/ack", adminToken());

        ResponseEntity<Map> resp = rest.exchange("/api/alerts/count", HttpMethod.GET,
                new HttpEntity<>(bearer(adminToken())), Map.class);
        assertThat(((Number) resp.getBody().get("open")).intValue()).isEqualTo(1);
    }

    @Test
    void agent_isForbidden() {
        String agentToken = createUserAndLogin("agent-alerts@supportiq.local", "agent1234", "AGENT");
        // `status()` et non `get()` : un 403 renvoie un ProblemDetail, donc un **objet** JSON, et le
        // helper `get()` est type `List`. La deserialisation echouerait avant meme d'arriver a
        // l'assertion sur le code — un test qui ne mesure alors plus ce qu'il croit mesurer.
        assertThat(status("/api/alerts", agentToken)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void withoutToken_isUnauthorized() {
        assertThat(rest.getForEntity("/api/alerts", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void detect_degradesWhenTheAiServiceIsUnreachable() {
        ResponseEntity<Map> resp = post("/api/alerts/detect", adminToken());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // --- helpers ---------------------------------------------------------------

    private void insert(String scope, String bucket, String severity, int observed, int expected) {
        jdbc.update("""
                INSERT INTO alerts (type, severity, scope, bucket_start, payload)
                VALUES ('VOLUME_ANOMALY', ?, ?, ?::timestamptz,
                        jsonb_build_object('observed', ?::int, 'expected', ?::int,
                                           'score', 7.2, 'method', 'stl'))
                """, severity, scope, bucket, observed, expected);
    }

    private long firstId() {
        return repository.recent(10).stream()
                .filter(a -> !a.acknowledged())
                .map(Alert::id)
                .findFirst()
                .orElseThrow();
    }

    private ResponseEntity<List> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
    }

    /**
     * Pour les cas d'erreur, ou seul le code compte.
     *
     * <p>{@code String.class} plutot que {@code List} ou {@code Map} : un chemin d'erreur ne rend
     * pas la meme forme qu'un chemin nominal — une liste devient un ProblemDetail, donc un objet —
     * et un helper type sur la forme nominale echoue a la deserialisation avant l'assertion. Une
     * chaine se lit quelle que soit la forme du corps.
     *
     * <p>Le meme piege s'est referme trois fois dans ce projet (DashboardIntegrationTest,
     * TopicIntegrationTest, ici). D'ou un helper dedie plutot qu'un troisieme correctif ponctuel.
     */
    private HttpStatusCode status(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class)
                .getStatusCode();
    }

    private ResponseEntity<Map> post(String path, String token) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class);
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
