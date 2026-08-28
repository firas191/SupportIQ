package com.supportiq.backend.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.supportiq.backend.common.error.AiServiceException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Client d'ingestion documentaire (S7-J4) — le seul du projet a envoyer du <b>multipart</b>.
 *
 * <p>Le piege qu'il couvre est particulier : sans nom de fichier dans la partie, le multipart part
 * sans extension, et le service IA ne peut plus choisir son extracteur — il refuse tout en 415.
 * C'est invisible a la compilation, invisible en relecture, et ne se voit qu'au premier depot.
 */
class IntakeClientTest {

    private MockRestServiceServer server;
    private IntakeClient client;

    @BeforeEach
    void setUp() {
        RestTemplate template = new RestTemplate();
        server = MockRestServiceServer.createServer(template);
        client = new IntakeClient((connect, read) -> template, "http://ai:8001");
    }

    @Test
    void theFileTravelsWithItsNameAndContent() {
        server.expect(requestTo("http://ai:8001/extract"))
                .andExpect(header("Content-Type", containsString(MediaType.MULTIPART_FORM_DATA_VALUE)))
                // **Le nom de fichier est dans la partie.** Sans lui, le service IA n'a pas
                // d'extension, donc pas d'extracteur, donc un 415 sur tous les documents.
                .andExpect(content().string(containsString("filename=\"demandes.pdf\"")))
                .andExpect(content().string(containsString("commande 48219")))
                .andRespond(withSuccess("""
                        {"pages":2,"method":"native","tickets":[
                          {"subject":"Commande 48219","body":"ma commande 48219 n'est pas arrivee",
                           "customer_email":"alice@example.com","language":"fr",
                           "confidence":{"subject":0.9,"body":0.95,"customer_email":0.8}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        IntakeModels.ExtractionResult result =
                client.extract("demandes.pdf", "ma commande 48219".getBytes(StandardCharsets.UTF_8));

        server.verify();
        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.method()).isEqualTo("native");
        assertThat(result.tickets()).hasSize(1);

        IntakeModels.ProposedTicket ticket = result.tickets().get(0);
        assertThat(ticket.subject()).isEqualTo("Commande 48219");
        assertThat(ticket.customerEmail()).isEqualTo("alice@example.com");
        // La confiance **par champ** traverse le plan de controle sans etre interpretee : c'est
        // l'interface qui decide de son seuil de surlignage.
        assertThat(ticket.confidence().customerEmail()).isEqualTo(0.8);
    }

    @Test
    void aMissingConfidenceBlockDoesNotBlowUp() {
        // Le service IA garantit ce bloc, mais un client HTTP ne doit jamais dependre de la
        // bienveillance de son interlocuteur : l'absence donne des zeros, donc trois champs
        // signales « a verifier », ce qui est le comportement prudent.
        server.expect(requestTo("http://ai:8001/extract"))
                .andRespond(withSuccess("""
                        {"pages":1,"method":"ocr","tickets":[{"subject":"S","body":"B"}]}
                        """, MediaType.APPLICATION_JSON));

        IntakeModels.ExtractionResult result = client.extract("x.txt", "B".getBytes());

        assertThat(result.method()).isEqualTo("ocr");
        assertThat(result.tickets().get(0).confidence().subject()).isZero();
        assertThat(result.tickets().get(0).customerEmail()).isNull();
    }

    @Test
    void a415KeepsItsStatusInsteadOfBecomingAFailure() {
        // « Ce format n'est pas accepte » n'est pas « reessayez plus tard ». Aplatir le 415 en 503
        // enverrait l'utilisateur redeposer indefiniment un fichier qui ne passera jamais.
        server.expect(requestTo("http://ai:8001/extract"))
                .andRespond(withStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                        .body("{\"detail\":\"Format .zip non pris en charge\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.extract("archive.zip", "PK".getBytes()))
                .isInstanceOf(AiServiceException.class)
                .extracting(e -> ((AiServiceException) e).status())
                .isEqualTo(415);
    }

    @Test
    void anUnreachableServiceDoesNotDegradeSilently() {
        // Contrairement a `SimilarTicketClient`, ce client **ne rend pas un resultat vide** en cas
        // de panne. Un lot vide ferait croire que le document ne contenait aucune demande — le
        // pire retour possible, puisque l'utilisateur recommencerait avec un autre fichier au lieu
        // de signaler l'incident.
        server.expect(requestTo("http://ai:8001/extract"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.extract("x.pdf", "x".getBytes()))
                .isInstanceOf(AiServiceException.class);
    }
}
