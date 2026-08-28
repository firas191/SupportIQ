package com.supportiq.backend.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.supportiq.backend.messaging.TicketsPersistedEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * La <b>seconde moitie</b> du trou de couverture : les frontieres transactionnelles.
 *
 * <p>Le defaut trouve a la premiere execution de la semaine 7 : {@code EmailPoller} portait
 * {@code @Transactional} sur une methode {@code protected} appelee depuis la meme classe. Spring ne
 * passe alors pas par le proxy, l'annotation est <b>inerte</b>, et il n'y a aucune transaction.
 *
 * <p>La consequence n'etait pas celle qu'on attend. Aucune erreur : les tickets etaient bien crees
 * (chaque {@code save} ouvre sa propre transaction). Mais {@code TicketsPersistedEvent}, publie
 * hors transaction, est <b>silencieusement ignore</b> par un
 * {@code @TransactionalEventListener(AFTER_COMMIT)}. Les tickets issus de courriels auraient donc
 * ete crees et <b>jamais analyses</b>, sans la moindre trace dans les journaux.
 *
 * <p>Ce test verifie que l'evenement arrive vraiment. Le dernier cas verifie l'inverse — qu'il
 * n'arrive <b>pas</b> hors transaction — et c'est lui qui documente le piege : sans cette
 * demonstration, la regle « la creation doit vivre dans un autre bean » ressemble a une precaution
 * arbitraire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Import(IntakeEventIntegrationTest.EventRecorder.class)
class IntakeEventIntegrationTest {

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
        // Aucun ordonnanceur ne doit s'inviter au milieu des assertions.
        registry.add("app.alerts.auto-detect", () -> "false");
        registry.add("app.topics.auto-detect", () -> "false");
        registry.add("app.sla.auto-score", () -> "false");
        registry.add("app.digest.auto-generate", () -> "false");
        registry.add("app.intake.email.enabled", () -> "false");
    }

    /**
     * Ecoute exactement comme {@code TicketEventPublisher} : meme evenement, meme phase.
     *
     * <p>Le point du test est la <b>phase</b>. Un {@code @EventListener} ordinaire recevrait
     * l'evenement dans les deux cas et ne prouverait rien.
     */
    // Pas de `@Component` : le scan de `com.supportiq.backend` atteint aussi les classes de test,
    // et cet enregistreur se retrouverait dans **tous** les contextes Spring du projet. Il est
    // ajoute explicitement par `@Import`, uniquement ici.
    static class EventRecorder {
        final List<TicketsPersistedEvent> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onPersisted(TicketsPersistedEvent event) {
            received.add(event);
        }
    }

    @Autowired
    IntakeService intake;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    EventRecorder recorder;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        recorder.received.clear();
        jdbc.update("DELETE FROM tickets WHERE source IN ('FILE', 'EMAIL')");
    }

    @Test
    void confirmingADocumentBatchPublishesAfterCommit() {
        var request = new IntakeModels.ConfirmRequest(List.of(
                new IntakeModels.ConfirmedTicket("Commande 48219", "corps 1", "a@example.com", "fr"),
                new IntakeModels.ConfirmedTicket("Order 77120", "body 2", "b@example.com", "en")));

        IntakeModels.ConfirmResult result = intake.confirm(request);

        assertThat(result.created()).isEqualTo(2);
        // **Un seul evenement pour le lot**, pas un par ticket : publier douze messages la ou un
        // suffit ferait douze allers-retours vers le broker pour la meme information.
        assertThat(recorder.received).hasSize(1);
        assertThat(recorder.received.get(0).tickets()).hasSize(2);
    }

    @Test
    void creatingFromAnEmailPublishesAfterCommit() {
        // **Le cas qui etait casse.** `EmailPoller` appelait sa propre methode `@Transactional`
        // `protected` : pas de proxy, pas de transaction, evenement ignore, ticket jamais analyse.
        Long id = intake.createFromEmail(
                "<msg-1@exemple.fr>", "client@exemple.fr", "Colis non recu", "corps du courriel");

        assertThat(id).isNotNull();
        assertThat(recorder.received).hasSize(1);
        assertThat(recorder.received.get(0).tickets().get(0).ticketId()).isEqualTo(id);
    }

    @Test
    void eachEmailIsItsOwnTransaction() {
        // Une transaction par courriel, et non une par releve : un echec sur le douzieme message
        // ne doit pas annuler les onze premiers, deja marques comme lus cote serveur IMAP —
        // auquel cas ils seraient definitivement perdus.
        intake.createFromEmail("<a@x>", "a@x.fr", "A", "corps a");
        intake.createFromEmail("<b@x>", "b@x.fr", "B", "corps b");

        assertThat(recorder.received).hasSize(2);
    }

    @Test
    void anEventPublishedOutsideATransactionIsSilentlyDropped() {
        // La demonstration du piege, en trois lignes.
        //
        // C'est **exactement** ce que faisait `EmailPoller` : l'evenement partait, personne ne le
        // recevait, et rien nulle part ne le signalait. C'est ce silence qui rend ce defaut
        // dangereux — une exception aurait ete decouverte en cinq minutes.
        publisher.publishEvent(new TicketsPersistedEvent(List.of()));

        assertThat(recorder.received)
                .as("un @TransactionalEventListener(AFTER_COMMIT) ignore un evenement hors transaction")
                .isEmpty();
    }
}
