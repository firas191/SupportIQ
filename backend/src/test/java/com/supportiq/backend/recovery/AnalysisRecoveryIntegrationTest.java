package com.supportiq.backend.recovery;

import static org.assertj.core.api.Assertions.assertThat;

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
 * S8-J1 : rattrapage des tickets echappes au pipeline d'analyse (V19).
 *
 * <p><b>Ce qui est teste, et ce qui ne l'est pas.</b> La republication vers RabbitMQ n'est pas
 * verifiee ici : aucun courtier ne tourne pendant les tests, donc {@code convertAndSend} echoue et
 * le service compte zero publication. C'est voulu — ce qui doit etre garanti n'est pas qu'un message
 * parte (le S7-J5 l'a demontre en conditions reelles), mais que <b>la selection des candidats soit
 * juste et bornee</b>. C'est elle qui, mal ecrite, transformerait un correctif en boucle de rejeu
 * infinie consommant du quota LLM.
 *
 * <p>La selection est deterministe et se teste sur une base reelle. La publication ne l'est pas.
 * Meme partage qu'au S5-J4 pour les brouillons : chaque garantie est verifiee la ou elle peut
 * l'etre sans dependre d'un service externe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalysisRecoveryIntegrationTest {

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
        // L'ordonnanceur s'inviterait au milieu des assertions et consommerait les tentatives que
        // les tests comptent. Meme precaution qu'au S7-J2 pour le detecteur d'anomalies.
        registry.add("app.analysis-recovery.enabled", () -> "false");
        // Plafond bas : deux tentatives suffisent a demontrer que le rejeu s'arrete, et le test
        // reste lisible.
        registry.add("app.analysis-recovery.max-attempts", () -> "2");
        // Pas de delai entre deux tentatives : le test veut enchainer les passages sans dormir.
        registry.add("app.analysis-recovery.retry-after", () -> "PT0S");
        registry.add("app.analysis-recovery.grace", () -> "PT30M");
        // **Port ferme, et non « pas de courtier ».** Sans cela, le test se connecte au RabbitMQ de
        // developpement publie sur localhost:5672 par docker compose : la publication reussit et
        // l'assertion tombe. Il passe ou echoue selon ce qui tourne a cote du poste — c'est-a-dire
        // qu'il ne teste rien.
        //
        // Meme precaution que dans AlertIntegrationTest pour le service IA, ou elle etait deja
        // ecrite noir sur blanc. Je ne l'ai pas appliquee ici, et la CI l'aurait laissee passer :
        // aucun courtier n'y tourne, le test y aurait ete vert pour la mauvaise raison.
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "1");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AnalysisRecoveryService service;

    @Autowired
    AnalysisRecoveryRepository repository;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM analysis_recovery");
        jdbc.update("DELETE FROM analyses");
        jdbc.update("DELETE FROM tickets");
    }

    // --- selection ---------------------------------------------------------------

    @Test
    void anOldTicketWithoutAnalysisIsACandidate() {
        long id = ticket("REC-1", "Paiement refuse", "2 hours");

        assertThat(candidateIds()).containsExactly(id);
    }

    @Test
    void aFreshTicketIsLeftAlone() {
        // Periode de grace. Un ticket cree il y a une minute a simplement son message en vol : le
        // republier doublerait le trafic nominal, et le rattrapage se prendrait pour un correctif
        // alors qu'il ne ferait qu'ajouter du travail.
        ticket("REC-2", "Trop recent", "1 minute");

        assertThat(candidateIds()).isEmpty();
    }

    @Test
    void anAnalysedTicketIsNeverACandidate() {
        long id = ticket("REC-3", "Deja analyse", "2 hours");
        analyse(id);

        assertThat(candidateIds()).isEmpty();
    }

    @Test
    void aTicketMarkedOutOfScopeIsNeverACandidate() {
        // Le corpus de charge est inscrit hors perimetre par la migration V19. La regle vit dans
        // une table, pas dans un filtre sur le prefixe cache au fond d'une requete : le jour ou un
        // vrai ticket porterait ce prefixe, il ne disparaitrait pas sans explication.
        long id = ticket("PERF-000001", "Ticket de charge", "2 hours");
        jdbc.update("INSERT INTO analysis_recovery (ticket_id, status) VALUES (?, 'OUT_OF_SCOPE')", id);

        assertThat(candidateIds()).isEmpty();
        assertThat(repository.status().outOfScope()).isEqualTo(1);
        // Et il ne gonfle pas le compteur d'anomalies : un indicateur qui affiche en permanence
        // 50 000 tickets en souffrance n'est plus un indicateur.
        assertThat(repository.status().unanalysed()).isZero();
    }

    // --- bornes ------------------------------------------------------------------

    @Test
    void attemptsAreBoundedAndTheTicketIsThenReported() {
        // **Le test qui justifie l'existence de la table.** Un balayage naif republierait
        // eternellement un ticket que le pipeline ne sait pas traiter, en consommant du quota LLM
        // tout en ayant l'air de fonctionner. Ici : deux tentatives, puis abandon — et l'abandon
        // n'est pas un effacement, c'est un signalement.
        long id = ticket("REC-4", "Ticket empoisonne", "2 hours");

        repository.recordAttempt(id, 2);
        assertThat(candidateIds()).containsExactly(id);
        assertThat(repository.status().pending()).isEqualTo(1);

        repository.recordAttempt(id, 2);
        assertThat(candidateIds()).isEmpty();

        RecoveryStatus status = repository.status();
        assertThat(status.givenUp()).isEqualTo(1);
        assertThat(status.pending()).isZero();
        // Il reste compte comme non analyse : abandonner de le republier ne fait pas disparaitre le
        // probleme, cela arrete seulement d'y depenser des jetons.
        assertThat(status.unanalysed()).isEqualTo(1);
    }

    @Test
    void aRecentlyRetriedTicketIsSkipped() {
        // Sans ce delai, un arriere important serait republie a chaque passage avant meme d'avoir
        // ete consomme : la file grossirait plus vite qu'elle ne se vide.
        long id = ticket("REC-5", "Deja retente", "2 hours");
        repository.recordAttempt(id, 5);

        assertThat(repository.findCandidates(java.time.Duration.ofMinutes(30), 5,
                java.time.Duration.ofHours(1), 10)).isEmpty();
    }

    @Test
    void aRecoveredTicketIsForgotten() {
        // Un compteur « en attente » qui ne redescend jamais a zero quand tout va bien cesse tres
        // vite d'etre regarde.
        long id = ticket("REC-6", "Rattrape", "2 hours");
        repository.recordAttempt(id, 5);
        assertThat(repository.status().pending()).isEqualTo(1);

        analyse(id);
        assertThat(repository.forgetRecovered()).isEqualTo(1);
        assertThat(repository.status().pending()).isZero();
    }

    @Test
    void pendingDoesNotCountTicketsThatWereFinallyAnalysed() {
        // Defaut trouve en recette. Le menage (`forgetRecovered`) n'a lieu qu'au debut du passage
        // suivant : pendant un quart d'heure, l'endpoint annoncait « 50 en attente » alors que les
        // 50 etaient analyses et la file vide.
        //
        // Le compteur est desormais derive de la verite, et non d'un menage suppose fait. Noter
        // qu'aucun `forgetRecovered()` n'est appele ici — c'est tout l'objet du test.
        long id = ticket("REC-9", "Analyse entre-temps", "2 hours");
        repository.recordAttempt(id, 5);
        assertThat(repository.status().pending()).isEqualTo(1);

        analyse(id);

        assertThat(repository.status().pending()).isZero();
        assertThat(repository.status().unanalysed()).isZero();
    }

    @Test
    void outOfScopeSurvivesTheCleanup() {
        // `forgetRecovered` ne doit pas emporter les exclusions : elles n'ont pas d'analyse, donc
        // rien ne les ferait revenir, mais une suppression trop large les rendrait a nouveau
        // candidates au prochain passage — 50 000 tickets de test d'un coup.
        long id = ticket("PERF-000002", "Charge", "2 hours");
        jdbc.update("INSERT INTO analysis_recovery (ticket_id, status) VALUES (?, 'OUT_OF_SCOPE')", id);

        repository.forgetRecovered();

        assertThat(repository.status().outOfScope()).isEqualTo(1);
        assertThat(candidateIds()).isEmpty();
    }

    // --- API ---------------------------------------------------------------------

    @Test
    void adminSeesTheState() {
        ticket("REC-7", "En souffrance", "2 hours");

        ResponseEntity<Map> resp = rest.exchange("/api/admin/analysis-recovery", HttpMethod.GET,
                new HttpEntity<>(bearer(adminToken())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("unanalysed")).intValue()).isEqualTo(1);
    }

    @Test
    void theManualBatchIsBounded() {
        // Une limite ecretee doit se voir : sans `requested` a cote de `published`, on croirait la
        // file vide alors qu'elle a simplement ete tronquee. Meme correctif qu'au S5-J5, ou
        // l'echantillon demande (50) et obtenu (8) divergeaient en silence.
        ResponseEntity<Map> resp = rest.exchange("/api/admin/analysis-recovery/run?limit=999999",
                HttpMethod.POST, new HttpEntity<>(bearer(adminToken())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("requested")).intValue()).isEqualTo(2000);
    }

    @Test
    void managerIsForbidden() {
        // La sante de la plateforme n'est pas une vue metier : un responsable d'equipe n'a rien a
        // faire d'un compteur de messages perdus, et ne saurait pas quoi en faire.
        String manager = createUserAndLogin("manager-rec@supportiq.local", "manager1234", "MANAGER");

        assertThat(status("/api/admin/analysis-recovery", manager))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aSweepWhosePublicationFailsDoesNotThrow() {
        // Le courtier est injoignable (port 1). La publication echoue pour chaque ticket, et le
        // service doit rendre la main proprement : un rattrapage qui propagerait l'exception ferait
        // echouer l'ordonnanceur toutes les quinze minutes pour une condition parfaitement attendue
        // — un courtier momentanement absent est exactement la situation que ce service existe pour
        // rattraper.
        long id = ticket("REC-8", "Publication impossible", "2 hours");

        assertThat(service.runOnce(10)).isZero();

        // Et la tentative est tout de meme comptee : elle a ete enregistree avant la publication.
        // C'est le prix assume de cet ordre — une tentative perdue plutot qu'un ticket sans trace,
        // qui redeviendrait candidat indefiniment.
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM analysis_recovery WHERE ticket_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    // --- helpers -----------------------------------------------------------------

    private long ticket(String ref, String subject, String age) {
        return jdbc.queryForObject("""
                INSERT INTO tickets (external_ref, source, status, subject, body, language, created_at)
                VALUES (?, 'FILE', 'NEW', ?, 'corps du ticket', 'fr', now() - ?::interval)
                RETURNING id
                """, Long.class, ref, subject, age);
    }

    private void analyse(long ticketId) {
        jdbc.update("""
                INSERT INTO analyses (ticket_id, priority, category, sentiment, keywords,
                                      confidence, model_used, escalated_to_llm)
                VALUES (?, 'MEDIUM', 'FACTURATION', 'NEU', '{}', 0.9, 'test', false)
                """, ticketId);
    }

    private java.util.List<Long> candidateIds() {
        return repository.findCandidates(java.time.Duration.ofMinutes(30), 2,
                        java.time.Duration.ZERO, 10)
                .stream().map(com.supportiq.backend.messaging.TicketCreatedEvent::ticketId).toList();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpStatusCode status(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class)
                .getStatusCode();
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
