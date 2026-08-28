package com.supportiq.backend.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Ce que ce test ferme, et pourquoi il n'existait pas.
 *
 * <p>Au S6-J3, ce client envoyait un corps <b>vide</b> : FastAPI repondait 422 « Field required »
 * sans jamais voir la question. Le defaut a vecu jusqu'a la premiere utilisation reelle depuis
 * l'interface.
 *
 * <p>Aucun test ne pouvait l'attraper, et c'est le point de methode : les suites d'integration
 * pointent toutes vers {@code localhost:1} pour verifier la <b>degradation</b> — que se passe-t-il
 * quand le service IA est absent. Personne ne verifiait qu'un appel <b>reussi</b> partait
 * correctement, et vu de Spring, un client casse et un service arrete se ressemblent.
 *
 * <p>{@link MockRestServiceServer} inverse le point de vue : il ne simule pas une panne, il
 * <b>inspecte la requete emise</b>. C'est la seule facon de constater qu'un corps est vide.
 */
class InsightClientTest {

    private static final String OK_BODY = """
            {"question":"q","sql":"SELECT 1","answer":"Un.","columns":["n"],"rows":[[1]],
             "row_count":1,"attempts":1,"truncated":false,
             "chart":{"type":"none","x":null,"y":null,"reason":"une seule valeur"}}
            """;

    private MockRestServiceServer server;
    private InsightClient client;

    @BeforeEach
    void setUp() {
        RestTemplate template = new RestTemplate();
        server = MockRestServiceServer.createServer(template);
        // La fabrique est une interface fonctionnelle : le test fournit son propre transport sans
        // qu'aucun constructeur « pour les tests » n'ait ete ajoute a la production.
        client = new InsightClient((connect, read) -> template, new ObjectMapper(), "http://ai:8001/");
    }

    @Test
    void theQuestionActuallyTravelsInTheBody() {
        server.expect(requestTo("http://ai:8001/agents/insight"))
                .andExpect(method(HttpMethod.POST))
                // **L'assertion qui aurait attrape le defaut du S6-J3.** Un corps vide passe tous
                // les autres controles : bonne URL, bonne methode, bon en-tete.
                .andExpect(content().string(containsString("Combien de tickets")))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        InsightAnswer answer = client.ask("Combien de tickets ?", "MANAGER");

        server.verify();
        assertThat(answer.sql()).isEqualTo("SELECT 1");
        assertThat(answer.answer()).isEqualTo("Un.");
    }

    @Test
    void theRoleIsSentEvenThoughTheServiceIgnoresIt() {
        // `user_role` fait partie du contrat §6 et le service IA l'accepte, mais il ne s'en sert
        // **pas** comme autorisation — le RBAC est ici, dans Spring. Le champ voyage quand meme :
        // le retirer du corps ferait diverger l'implementation du contrat publie.
        server.expect(requestTo("http://ai:8001/agents/insight"))
                .andExpect(content().string(containsString("user_role")))
                .andExpect(content().string(containsString("MANAGER")))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        client.ask("q", "MANAGER");
        server.verify();
    }

    @Test
    void anAccentedQuestionSurvivesTheEncoding() {
        // Second defaut du S6-J3, distinct du premier : un `HttpEntity<String>` dont le
        // Content-Type ne porte pas de charset est ecrit en ISO-8859-1. Tant que le texte est en
        // ASCII pur les deux encodages coincident, ce qui masque le probleme jusqu'au premier
        // accent — et FastAPI refuse alors le corps avant meme d'atteindre l'agent.
        server.expect(requestTo("http://ai:8001/agents/insight"))
                .andExpect(content().string(containsString("délai de résolution")))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        client.ask("Quel est le délai de résolution moyen ?", "MANAGER");
        server.verify();
    }

    @Test
    void theTrailingSlashOfTheBaseUrlIsNormalised() {
        // Le client recoit « http://ai:8001/ ». Sans normalisation l'URL serait
        // « http://ai:8001//agents/insight » — techniquement valide, et le genre de detail qui
        // casse le jour ou un proxy strict se met en travers.
        server.expect(requestTo("http://ai:8001/agents/insight"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        client.ask("q", "MANAGER");
        server.verify();
    }

    @Test
    void a422KeepsItsStatus() {
        // 422 « hors perimetre » et 503 « panne » se lisent tres differemment cote interface : un
        // refus legitime n'est pas une defaillance, et les aplatir en 500 les rendrait
        // indiscernables.
        server.expect(requestTo("http://ai:8001/agents/insight"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body("{\"detail\":\"Question hors perimetre\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.ask("Donne-moi les salaires", "MANAGER"))
                .isInstanceOf(InsightException.class)
                .extracting(e -> ((InsightException) e).status())
                .isEqualTo(422);
    }

    @Test
    void aServerFailureBecomesUnavailable() {
        server.expect(requestTo("http://ai:8001/agents/insight")).andRespond(withServerError());

        assertThatThrownBy(() -> client.ask("q", "MANAGER"))
                .isInstanceOf(InsightException.class);
    }
}
