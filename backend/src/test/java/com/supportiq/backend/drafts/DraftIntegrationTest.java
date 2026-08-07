package com.supportiq.backend.drafts;

import static org.assertj.core.api.Assertions.assertThat;

import com.supportiq.backend.tickets.Ticket;
import com.supportiq.backend.tickets.TicketRepository;
import com.supportiq.backend.tickets.TicketSource;
import com.supportiq.backend.tickets.TicketStatus;
import java.util.HashMap;
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
 * S5-J4 : boucle de validation des brouillons.
 *
 * <p><b>Ce qui est teste ici, et pourquoi ce n'est pas la generation.</b> Generer un brouillon
 * demande un modele de langage : le resultat varie d'un appel a l'autre, l'assertion serait
 * instable et la CI dependrait d'une cle d'API. Ce qui doit etre garanti par un test, c'est ce qui
 * ne doit <b>jamais</b> varier — la machine a etats. Les brouillons sont donc inseres directement,
 * comme le ferait le service IA, et les transitions sont exercees via l'API.
 *
 * <p>Le service IA pointe volontairement vers un port ferme : aucun test ici n'a besoin de lui.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DraftIntegrationTest {

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
        registry.add("app.ai-service.base-url", () -> "http://localhost:1");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    TicketRepository tickets;

    @Autowired
    JdbcTemplate jdbc;

    private Long ticketId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM draft_responses");
        jdbc.update("DELETE FROM kb_documents");
        tickets.deleteAll();
        ticketId = tickets.save(Ticket.builder()
                .externalRef("DR-1").source(TicketSource.FILE).status(TicketStatus.NEW)
                .subject("Double debit").body("J'ai ete debite deux fois").language("fr")
                .build()).getId();
    }

    // --- Lecture ---------------------------------------------------------------

    @Test
    void latest_returns204WhenNoDraftYet() {
        // Absence de brouillon = etat nominal d'un ticket qu'on ouvre, pas une erreur : l'interface
        // ne doit pas avoir a traiter le cas courant dans sa branche d'echec.
        ResponseEntity<String> resp = rest.exchange("/api/tickets/" + ticketId + "/draft",
                HttpMethod.GET, new HttpEntity<>(bearer()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void latest_hydratesCitationWithFullPassage() {
        long chunkId = insertChunk("faq-facturation.md", "Facturation > Double debit",
                "Un double debit est rembourse sous 7 jours ouvres apres verification bancaire.");
        insertDraft("Bonjour, vous serez rembourse [1].",
                "[{\"marker\":1,\"chunk_id\":" + chunkId + ",\"source\":\"faq-facturation.md\","
                        + "\"heading\":\"Facturation > Double debit\",\"excerpt\":\"Un double debit\"}]",
                false, false);

        Map body = getDraft();
        List<Map> citations = (List<Map>) body.get("citations");
        assertThat(citations).hasSize(1);
        // Le passage complet, pas l'extrait tronque : une troncature peut couper la clause qui
        // nuance l'affirmation, et l'agent validerait sur une source amputee.
        assertThat((String) citations.get(0).get("content")).contains("verification bancaire");
        assertThat(citations.get(0).get("stale")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void latest_marksCitationStaleWhenChunkDisappeared() {
        // Les identifiants de fragment changent a chaque re-import (remplacement transactionnel,
        // S5-J1) : un brouillon un peu ancien cite alors un fragment disparu.
        insertDraft("Reponse [1].",
                "[{\"marker\":1,\"chunk_id\":999999,\"source\":\"faq.md\",\"heading\":\"H\","
                        + "\"excerpt\":\"copie conservee\"}]",
                false, false);

        Map body = getDraft();
        List<Map> citations = (List<Map>) body.get("citations");
        assertThat(citations.get(0).get("stale")).isEqualTo(true);
        assertThat((String) citations.get(0).get("content")).isEqualTo("copie conservee");
    }

    @Test
    void latest_ignoresRejectedDrafts() {
        long rejected = insertDraft("Ancien brouillon ecarte.", "[]", false, false);
        review(rejected, "REJECTED", null);

        ResponseEntity<String> resp = rest.exchange("/api/tickets/" + ticketId + "/draft",
                HttpMethod.GET, new HttpEntity<>(bearer()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // --- Machine a etats -------------------------------------------------------

    @Test
    void approve_recordsReviewerAndTimestamp() {
        long id = insertDraft("Bonjour, voici la marche a suivre [1].", "[]", false, false);

        Map body = review(id, "SENT", null).getBody();
        assertThat(body.get("status")).isEqualTo("SENT");
        assertThat(body.get("reviewedBy")).isEqualTo("admin@supportiq.local");
        assertThat(body.get("reviewedAt")).isNotNull();
        // Valide tel quel : la colonne reste nulle. C'est ce qui distingue « approuve sans
        // retouche » de « approuve apres reecriture » — la mesure du S5-J5.
        assertThat(body.get("finalContent")).isNull();
    }

    @Test
    void edit_keepsModelOutputIntact() {
        long id = insertDraft("Texte du modele.", "[]", false, false);

        Map body = review(id, "EDITED", "Texte reecrit par l'agent.").getBody();
        assertThat(body.get("status")).isEqualTo("EDITED");
        assertThat(body.get("finalContent")).isEqualTo("Texte reecrit par l'agent.");
        // Le juge automatique du S5-J5 note le modele : s'il lisait un texte reecrit par un
        // humain, il noterait l'humain.
        assertThat(body.get("content")).isEqualTo("Texte du modele.");
    }

    @Test
    void edit_thenApprove_keepsHumanVersion() {
        long id = insertDraft("Texte du modele.", "[]", false, false);
        review(id, "EDITED", "Version corrigee.");

        Map body = review(id, "SENT", null).getBody();
        assertThat(body.get("status")).isEqualTo("SENT");
        assertThat(body.get("finalContent")).isEqualTo("Version corrigee.");
    }

    @Test
    void review_rejectsDecisionOnTerminalDraft() {
        long id = insertDraft("Deja tranche.", "[]", false, false);
        review(id, "SENT", null);

        assertThat(review(id, "REJECTED", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void review_refusesToApproveAnAbstention() {
        // Garde-fou de fond : le texte d'abstention s'adresse a l'agent (« a traiter
        // manuellement »), pas au client. Le masquer dans l'interface ne suffit pas — une regle
        // qui n'existe qu'en CSS n'est pas une regle.
        long id = insertDraft("Je n'ai pas trouve d'information couvrant cette demande.", "[]",
                false, true);

        assertThat(review(id, "SENT", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Ecarter reste possible : c'est la sortie legitime de ce cas.
        assertThat(review(id, "REJECTED", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void review_rejectsUnknownStatus() {
        long id = insertDraft("Texte.", "[]", false, false);
        // PROPOSED n'est pas une decision humaine : seul l'agent le pose.
        assertThat(review(id, "PROPOSED", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(review(id, "ARCHIVED", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void review_rejectsEditWithoutChange() {
        long id = insertDraft("Texte identique.", "[]", false, false);
        assertThat(review(id, "EDITED", "Texte identique.").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void review_returns404OnUnknownDraft() {
        assertThat(review(999_999L, "SENT", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void endpoints_require_authentication() {
        ResponseEntity<String> resp = rest.getForEntity(
                "/api/tickets/" + ticketId + "/draft", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers ---------------------------------------------------------------

    private long insertDraft(String content, String citationsJson, boolean lowConfidence,
            boolean abstained) {
        return jdbc.queryForObject("""
                INSERT INTO draft_responses
                    (ticket_id, content, citations, tone, low_confidence, issues, attempts, abstained)
                VALUES (?, ?, ?::jsonb, 'formal', ?, '{}', 1, ?)
                RETURNING id
                """, Long.class, ticketId, content, citationsJson, lowConfidence, abstained);
    }

    private long insertChunk(String source, String heading, String content) {
        return jdbc.queryForObject("""
                INSERT INTO kb_documents (title, source, chunk_index, heading, content, model)
                VALUES ('FAQ', ?, 0, ?, ?, 'e5')
                RETURNING id
                """, Long.class, source, heading, content);
    }

    @SuppressWarnings("unchecked")
    private Map getDraft() {
        ResponseEntity<Map> resp = rest.exchange("/api/tickets/" + ticketId + "/draft",
                HttpMethod.GET, new HttpEntity<>(bearer()), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> review(long draftId, String status, String content) {
        HttpHeaders headers = bearer();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", status);
        payload.put("content", content);
        return rest.exchange("/api/drafts/" + draftId, HttpMethod.PATCH,
                new HttpEntity<>(payload, headers), Map.class);
    }

    private HttpHeaders bearer() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken());
        return headers;
    }

    @SuppressWarnings("unchecked")
    private String adminToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("email", "admin@supportiq.local", "password", "admin1234"),
                        headers),
                Map.class);
        return (String) resp.getBody().get("accessToken");
    }
}
