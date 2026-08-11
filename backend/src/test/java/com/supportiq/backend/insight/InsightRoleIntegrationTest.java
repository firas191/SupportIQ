package com.supportiq.backend.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * S6-J1 — <b>seconde barriere</b> du text-to-SQL : les droits PostgreSQL du role {@code insight_ro}.
 *
 * <p>La premiere barriere (validation AST par sqlglot) est testee cote service IA, sans base. Elle
 * ne prouve rien sur ce que la base autorise reellement : une liste blanche en Python et un GRANT en
 * SQL peuvent diverger, et c'est precisement l'ecart qu'un attaquant cherche.
 *
 * <p>Ce test pose donc la seule question qui compte : <b>si la validation AST tombait entierement,
 * que pourrait faire le SQL genere ?</b> Reponse attendue : lire six vues d'agregats sans donnee
 * personnelle, et rien d'autre. Il se connecte directement en {@code insight_ro}, sans passer par
 * l'application, exactement comme le ferait une requete ayant contourne la garde.
 */
@SpringBootTest
@Testcontainers
class InsightRoleIntegrationTest {

    private static final String INSIGHT_PASSWORD = "insight-test";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Le mot de passe du role traverse Flyway par placeholder (V11) : le test verifie donc
        // aussi que ce cablage fonctionne, pas seulement les GRANT.
        registry.add("spring.flyway.placeholders.insight_password", () -> INSIGHT_PASSWORD);
        registry.add("app.security.jwt.secret",
                () -> "test-secret-supportiq-0123456789-abcdefghijklmnop");
        registry.add("app.bootstrap.admin.email", () -> "admin@supportiq.local");
        registry.add("app.bootstrap.admin.password", () -> "admin1234");
        registry.add("app.ai-service.base-url", () -> "http://localhost:1");
    }

    /** Connexion etablie <b>en tant que insight_ro</b>, pas en tant qu'application. */
    private Connection asInsight() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "insight_ro", INSIGHT_PASSWORD);
    }

    // --- Ce que le role DOIT pouvoir faire --------------------------------------

    @Test
    void canReadTheAllowedViews() throws SQLException {
        try (Connection conn = asInsight(); Statement st = conn.createStatement()) {
            for (String view : new String[] {
                    "v_tickets", "v_daily_volume", "v_draft_activity",
                    "v_ticket_stats", "v_category_trends", "v_hourly_load"}) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + view)) {
                    assertThat(rs.next()).as("lecture de %s", view).isTrue();
                }
            }
        }
    }

    // --- Ce que le role ne DOIT PAS pouvoir faire -------------------------------

    @Test
    void cannotReadTheUsersTable() throws SQLException {
        // Le pire scenario : mots de passe hashes et adresses e-mail de toute l'equipe.
        assertDenied("SELECT email, password_hash FROM users");
    }

    @Test
    void cannotReadRawTicketsWithPersonalData() throws SQLException {
        // `v_tickets` existe justement pour exposer les tickets SANS `customer_email` ni `body`.
        // Si la table brute restait lisible, la vue ne serait qu'une decoration.
        assertDenied("SELECT customer_email, body FROM tickets");
    }

    @Test
    void cannotReadTheKnowledgeBaseOrTheDraftTexts() throws SQLException {
        assertDenied("SELECT content FROM kb_documents");
        assertDenied("SELECT content FROM draft_responses");
    }

    @Test
    void cannotReadRefreshTokens() throws SQLException {
        assertDenied("SELECT token_hash FROM refresh_tokens");
    }

    @Test
    void cannotWriteAnywhere() throws SQLException {
        // Meme sur une vue autorisee en lecture : `default_transaction_read_only` bloque avant
        // meme la question des droits sur l'objet.
        assertDenied("INSERT INTO tickets (subject) VALUES ('x')");
        assertDenied("UPDATE tickets SET subject = 'x'");
        assertDenied("DELETE FROM tickets");
        assertDenied("CREATE TABLE evil (id INT)");
    }

    @Test
    void cannotEscalateItsOwnPrivileges() throws SQLException {
        assertDenied("GRANT SELECT ON users TO insight_ro");
        assertDenied("ALTER ROLE insight_ro SUPERUSER");
    }

    @Test
    void transactionsAreReadOnlyByDefault() throws SQLException {
        try (Connection conn = asInsight(); Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SHOW transaction_read_only")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("on");
        }
    }

    @Test
    void aStatementTimeoutIsEnforcedAtRoleLevel() throws SQLException {
        // Sans plafond, une jointure croisee sur 10 000 tickets immobiliserait une connexion.
        // Le service pose deja le sien ; celui-ci tient meme si le service oublie.
        try (Connection conn = asInsight(); Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SHOW statement_timeout")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("5s");
        }
    }

    private void assertDenied(String sql) throws SQLException {
        try (Connection conn = asInsight(); Statement st = conn.createStatement()) {
            assertThatThrownBy(() -> st.execute(sql))
                    .as("la requete aurait du etre refusee : %s", sql)
                    .isInstanceOf(SQLException.class);
        }
    }
}
