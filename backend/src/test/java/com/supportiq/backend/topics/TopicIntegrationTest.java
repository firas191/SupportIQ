package com.supportiq.backend.topics;

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
 * S7-J1 : lecture des sujets emergents sur PostgreSQL reel (V15).
 *
 * <p>Ce qui est teste ici est la <b>lecture</b>, pas la detection. Detecter demande des embeddings,
 * une reduction de dimension et un appel de modele par sujet : non deterministe, lent, et dependant
 * d'une cle d'API. Meme arbitrage qu'au S5-J4 pour les brouillons — on couvre ce dont on peut
 * garantir le comportement.
 *
 * <p>Et ce qu'il y a a garantir n'est pas mince : ne jamais melanger deux instantanes. Un
 * regroupement non supervise donne des groupes differents a chaque execution ; afficher ensemble
 * ceux de mardi et de mercredi montrerait le meme sujet deux fois avec des chiffres qui se
 * contredisent, sans que rien dans l'ecran ne permette de le comprendre.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TopicIntegrationTest {

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
        // La detection nocturne n'a rien a faire dans une suite de tests : elle appellerait un
        // service IA absent et polluerait les journaux d'echecs sans rapport avec l'assertion.
        registry.add("app.topics.auto-detect", () -> "false");
        // Port ferme, comme dans les autres suites : le test de degradation doit echouer sur une
        // connexion refusee, pas sur un service IA qui tournerait par hasard sur le poste du
        // developpeur — auquel cas il declencherait une vraie detection de plusieurs minutes.
        registry.add("app.ai-service.base-url", () -> "http://localhost:1");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM topics");

        // Instantane ANCIEN — il ne doit jamais apparaitre dans la reponse.
        insert("2026-08-01 03:30:00+00", "Ancien sujet", 40, 30, 10, 200.0, "TECHNIQUE");

        // Instantane COURANT.
        insert("2026-08-02 03:30:00+00", "Double debit carte", 24, 18, 6, 200.0, "FACTURATION");
        insert("2026-08-02 03:30:00+00", "Colis jamais recu", 30, 15, 15, 0.0, "TECHNIQUE");
        insert("2026-08-02 03:30:00+00", "Nouveau motif", 12, 12, 0, null, null);
    }

    @Test
    void onlyTheLatestSnapshotIsReturned() {
        ResponseEntity<Map> resp = get("/api/topics", adminToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> topics = (List<Map<String, Object>>) resp.getBody().get("topics");
        assertThat(topics).hasSize(3);
        assertThat(topics).extracting(t -> t.get("label")).doesNotContain("Ancien sujet");
    }

    @Test
    void topicsAreOrderedByGrowthWithNewOnesLast() {
        // Le tri repond a « qu'est-ce qui bouge ? », pas a « qu'est-ce qui est gros ? » — cette
        // derniere question a deja son ecran depuis le S4-J1.
        ResponseEntity<Map> resp = get("/api/topics", adminToken());

        List<Map<String, Object>> topics = (List<Map<String, Object>>) resp.getBody().get("topics");
        assertThat(topics).extracting(t -> t.get("label"))
                .containsExactly("Double debit carte", "Colis jamais recu", "Nouveau motif");
    }

    @Test
    void aBrandNewTopicHasNoGrowthRatherThanZero() {
        // `null` n'est pas 0 : il dit « rien a quoi comparer ». Renvoyer 0 ferait lire « stable »
        // sur un sujet qui vient d'apparaitre, soit l'exact contraire de la verite.
        ResponseEntity<Map> resp = get("/api/topics", adminToken());

        List<Map<String, Object>> topics = (List<Map<String, Object>>) resp.getBody().get("topics");
        Map<String, Object> fresh = topics.stream()
                .filter(t -> "Nouveau motif".equals(t.get("label"))).findFirst().orElseThrow();
        assertThat(fresh.get("growth")).isNull();
        assertThat(((Number) fresh.get("previousCount")).intValue()).isZero();
    }

    @Test
    void sampleTicketIdsSurviveTheArrayColumn() {
        // Le tableau `BIGINT[]` traverse JDBC puis Jackson : c'est le genre de conversion qui
        // marche a la compilation et casse a la premiere ligne reelle.
        ResponseEntity<Map> resp = get("/api/topics", adminToken());

        List<Map<String, Object>> topics = (List<Map<String, Object>>) resp.getBody().get("topics");
        List<Number> samples = (List<Number>) topics.get(0).get("sampleTicketIds");

        // On compare des `long`, pas les objets bruts : Jackson deserialise un entier en `Integer`
        // ou en `Long` selon sa magnitude. Asserter sur les objets ferait passer ce test avec des
        // identifiants a trois chiffres et echouer le jour ou la base en produit de plus grands —
        // un test qui se casse pour une raison sans rapport avec ce qu'il verifie.
        assertThat(samples).extracting(Number::longValue).containsExactly(101L, 102L, 103L);
    }

    @Test
    void anEmptySnapshotIsNotAnError() {
        // Aucun sujet detecte est un resultat normal : le corpus recent peut ne contenir aucun
        // groupe assez dense. `computedAt` a null dit « jamais calcule », ce que l'interface
        // distingue de « calcule, rien trouve ».
        jdbc.update("DELETE FROM topics");
        ResponseEntity<Map> resp = get("/api/topics", adminToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) resp.getBody().get("topics")).isEmpty();
        assertThat(resp.getBody().get("computedAt")).isNull();
    }

    @Test
    void agent_isForbidden() {
        String agentToken = createUserAndLogin("agent-topics@supportiq.local", "agent1234", "AGENT");
        assertThat(get("/api/topics", agentToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void withoutToken_isUnauthorized() {
        assertThat(rest.getForEntity("/api/topics", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void detect_degradesWhenTheAiServiceIsUnreachable() {
        // `app.ai-service.base-url` pointe sur un service absent pendant les tests : l'echec doit
        // arriver en 503 « momentanement indisponible », pas en 500 anonyme. Un manager qui lit
        // « erreur interne » appelle le support ; « momentanement indisponible » se reessaie.
        ResponseEntity<Map> resp = rest.exchange("/api/topics/detect", HttpMethod.POST,
                new HttpEntity<>(bearer(adminToken())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // --- helpers ---------------------------------------------------------------

    private void insert(String computedAt, String label, int size, int recent, int previous,
            Double growth, String category) {
        jdbc.update("""
                INSERT INTO topics (computed_at, window_days, label, size, recent_count,
                                    previous_count, growth, sample_ticket_ids, top_category)
                VALUES (?::timestamptz, 14, ?, ?, ?, ?, ?::numeric,
                        ARRAY[101, 102, 103]::bigint[], ?::varchar)
                """, computedAt, label, size, recent, previous, growth, category);
        // Les casts explicites ne sont pas decoratifs : PostgreSQL refuse un parametre NULL dont il
        // ne peut pas deduire le type, et `growth` comme `top_category` sont nuls sur un sujet
        // nouveau — exactement le cas que ces tests existent pour couvrir.
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
