package com.supportiq.backend.drafts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Le client qui a dormi casse le plus longtemps.
 *
 * <p>Comme {@code InsightClient}, il envoyait un corps vide depuis sa creation au S5-J4. Le defaut
 * n'y a ete decouvert qu'au S6-J3, et seulement <b>par ricochet</b> : la generation de brouillon
 * n'avait jamais ete exercee depuis l'interface, le S5-J5 appelant l'agent directement dans le
 * conteneur. Il serait sorti a la premiere demonstration devant l'encadrant.
 *
 * <p>C'est la meilleure illustration du trou : un code qui n'est teste que dans son mode degrade
 * peut etre entierement non fonctionnel sans qu'aucune suite ne devienne rouge.
 */
class DraftClientTest {

    private MockRestServiceServer server;
    private DraftClient client;

    @BeforeEach
    void setUp() {
        RestTemplate template = new RestTemplate();
        server = MockRestServiceServer.createServer(template);
        client = new DraftClient((connect, read) -> template, "http://ai:8001");
    }

    @Test
    void theTicketAndToneActuallyTravel() {
        server.expect(requestTo("http://ai:8001/agents/resolution"))
                .andExpect(content().string(containsString("\"ticket_id\":10020")))
                .andExpect(content().string(containsString("\"tone\":\"empathetic\"")))
                .andRespond(withSuccess("{\"draft_id\":42}", MediaType.APPLICATION_JSON));

        assertThat(client.generate(10020, "empathetic")).isEqualTo(42L);
        server.verify();
    }

    @Test
    void anAbsentDraftIdIsNullNotAnError() {
        // L'agent renvoie toujours le brouillon dans la reponse HTTP, meme quand la persistance a
        // echoue (S5-J3) : `draft_id` absent signifie « non persiste », pas « echec ».
        server.expect(requestTo("http://ai:8001/agents/resolution"))
                .andRespond(withSuccess("{\"content\":\"...\"}", MediaType.APPLICATION_JSON));

        assertThat(client.generate(1, "formal")).isNull();
    }

    @Test
    void anUnknownTicketBecomesAConflictNotAServerError() {
        // Le service IA ne connait pas ce ticket : il n'y a rien a rediger. C'est une erreur de la
        // demande, pas une panne — et l'interface doit pouvoir le dire autrement qu'en « reessayez ».
        server.expect(requestTo("http://ai:8001/agents/resolution"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.generate(999, "formal"))
                .isInstanceOf(DraftException.class)
                .extracting(e -> ((DraftException) e).status())
                .isEqualTo(409);
    }

    @Test
    void anUnreachableServiceIsUnavailable() {
        server.expect(requestTo("http://ai:8001/agents/resolution"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.generate(1, "formal"))
                .isInstanceOf(DraftException.class);
    }
}
