package com.supportiq.backend.sla;

import static org.assertj.core.api.Assertions.assertThat;

import com.supportiq.backend.tickets.Ticket;
import com.supportiq.backend.tickets.TicketRepository;
import com.supportiq.backend.tickets.TicketSource;
import com.supportiq.backend.tickets.TicketStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * S8-J1 : l'echeance SLA existe des la creation, puis s'affine.
 *
 * <p>Le defaut corrige ici n'aurait ete visible sur aucun test existant, parce qu'il ne produit
 * <b>rien</b> : un ticket sans echeance ne leve pas d'exception, il est simplement rejete en queue
 * de tous les tris {@code NULLS LAST} — celui du lot de scoring et celui de la liste. Il sort du
 * dispositif SLA en silence. C'est le meme mode de defaillance que les messages perdus du S7-J5, sur
 * un autre chemin.
 */
@SpringBootTest
@Testcontainers
class SlaDueDateIntegrationTest {

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
        registry.add("spring.rabbitmq.port", () -> "1");
    }

    @Autowired
    TicketRepository tickets;

    @Autowired
    SlaRepository slaRepository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM analyses");
        tickets.deleteAll();
    }

    @Test
    void everyNewTicketGetsAProvisionalDeadline() {
        Ticket saved = tickets.save(base().build());

        // Sans cette valeur, le ticket serait invisible au lot de scoring (trie par
        // `sla_due_at DESC NULLS LAST`, plafonne) et repousse en fin de liste.
        assertThat(saved.getSlaDueAt()).isNotNull();
        assertThat(Duration.between(saved.getCreatedAt(), saved.getSlaDueAt()))
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    void theDeadlineFollowsTheCreationDateAndNotTheInsertionTime() {
        // Un import porte les dates d'origine du fichier. Calculer l'echeance a partir de l'instant
        // d'insertion donnerait a un ticket vieux de trois jours un delai de reponse qui commence
        // aujourd'hui — et une file d'import entierement « dans les temps » le jour du chargement.
        Instant threeDaysAgo = threeDaysAgo();
        Ticket saved = tickets.save(base().createdAt(threeDaysAgo).build());

        assertThat(saved.getSlaDueAt()).isEqualTo(threeDaysAgo.plus(Duration.ofHours(24)));
        assertThat(saved.getSlaDueAt()).isBefore(Instant.now());
    }

    @Test
    void anExplicitDeadlineIsNeverOverwritten() {
        Instant chosen = Instant.now().plus(Duration.ofHours(6));
        Ticket saved = tickets.save(base().slaDueAt(chosen).build());

        assertThat(saved.getSlaDueAt()).isEqualTo(chosen);
    }

    @Test
    void analysisTightensTheDeadlineOfAnOldTicketIntoThePast() {
        // Le cas qui justifie de partir de `created_at` et non de l'instant de l'analyse : un ticket
        // de trois jours dont on decouvre qu'il etait urgent **etait** en retard. On l'ignorait,
        // c'est tout.
        Instant threeDaysAgo = threeDaysAgo();
        Ticket saved = tickets.save(base().createdAt(threeDaysAgo).build());

        slaRepository.applyDueDate(saved.getId(), "HIGH");

        assertThat(dueAt(saved.getId())).isEqualTo(threeDaysAgo.plus(Duration.ofHours(4)));
    }

    @Test
    void analysisMayAlsoPushTheDeadlineBack() {
        // **Comportement volontaire, et le seul qui se discute.** Une priorite LOW repousse
        // l'echeance, et un ticket peut donc cesser d'apparaitre en depassement. C'est correct : la
        // valeur provisoire n'etait pas un engagement, c'etait une hypothese faute d'information, et
        // une hypothese se corrige dans les deux sens.
        Instant createdAt = threeDaysAgo();
        Ticket saved = tickets.save(base().createdAt(createdAt).build());
        Instant provisional = saved.getSlaDueAt();

        slaRepository.applyDueDate(saved.getId(), "LOW");

        assertThat(dueAt(saved.getId())).isAfter(provisional);
        assertThat(dueAt(saved.getId())).isEqualTo(createdAt.plus(Duration.ofHours(72)));
    }

    @Test
    void aResolvedTicketKeepsItsDeadline() {
        // Une analyse peut arriver apres la resolution (re-analyse, rattrapage de file). Deplacer
        // alors l'echeance reecrirait retroactivement la verite terrain d'un depassement deja
        // constate — un historique qui se corrige tout seul ne se mesure plus.
        Ticket saved = tickets.save(base().createdAt(threeDaysAgo()).build());
        Instant provisional = saved.getSlaDueAt();
        slaRepository.markResolved(saved.getId(), Instant.now());

        slaRepository.applyDueDate(saved.getId(), "HIGH");

        assertThat(dueAt(saved.getId())).isEqualTo(provisional);
    }

    /**
     * Date de creation <b>tronquee a la milliseconde</b>.
     *
     * <p>{@code Instant.now()} peut porter des nanosecondes, {@code timestamptz} s'arrete a la
     * microseconde : une valeur relue de la base ne serait alors pas strictement egale a celle
     * gardee en memoire, et l'assertion echouerait un jour sur deux pour une raison sans aucun
     * rapport avec ce qu'elle verifie.
     */
    private static Instant threeDaysAgo() {
        return Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
    }

    private Instant dueAt(long ticketId) {
        return jdbc.queryForObject("SELECT sla_due_at FROM tickets WHERE id = ?",
                java.sql.Timestamp.class, ticketId).toInstant();
    }

    private Ticket.TicketBuilder base() {
        return Ticket.builder()
                .externalRef("SLA-" + System.nanoTime())
                .source(TicketSource.FILE)
                .status(TicketStatus.NEW)
                .subject("Paiement refuse")
                .body("corps du ticket")
                .language("fr");
    }
}
