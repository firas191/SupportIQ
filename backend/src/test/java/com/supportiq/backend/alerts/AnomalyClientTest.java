package com.supportiq.backend.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportiq.backend.common.error.AiServiceException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Client du detecteur d'anomalies (S7-J2).
 *
 * <p>Le piege couvert ici n'est pas le corps de la requete mais la <b>lecture de la reponse</b> :
 * Python serialise un instant en {@code 2026-08-28T08:00:00+00:00}, alors que
 * {@code Instant.parse} n'accepte que le suffixe {@code Z}. Un {@code Instant.parse} aurait
 * compile sans broncher et leve une {@code DateTimeParseException} a la premiere anomalie reelle —
 * c'est-a-dire le jour ou le detecteur sert enfin a quelque chose.
 *
 * <p>Meme famille que le {@code NUMERIC} lu en {@code Double} du S4-J4 : une conversion qui
 * traverse une frontiere, correcte a la compilation, fausse a la premiere ligne reelle.
 */
class AnomalyClientTest {

    private MockRestServiceServer server;
    private AnomalyClient client;

    @BeforeEach
    void setUp() {
        RestTemplate template = new RestTemplate();
        server = MockRestServiceServer.createServer(template);
        client = new AnomalyClient((connect, read) -> template, new ObjectMapper(), "http://ai:8001");
    }

    @Test
    void aPythonOffsetTimestampIsParsed() {
        server.expect(requestTo("http://ai:8001/anomaly/detect"))
                .andExpect(content().string(containsString("lookback")))
                .andRespond(withSuccess("""
                        {"window_hours":336,"categories":4,"anomalies":[
                          {"scope":"FACTURATION","bucket_start":"2026-08-28T08:00:00+00:00",
                           "severity":"CRITICAL","observed":30,"expected":12.63,"score":22.71,
                           "method":"stl",
                           "payload":{"observed":30,"expected":12.63,"score":22.71,"method":"stl"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<AnomalyClient.Candidate> found = client.detect(3);

        server.verify();
        assertThat(found).hasSize(1);
        // `+00:00` et non `Z` : c'est exactement la forme que produit `datetime.isoformat()`.
        assertThat(found.get(0).bucketStart()).isEqualTo(Instant.parse("2026-08-28T08:00:00Z"));
        assertThat(found.get(0).scope()).isEqualTo("FACTURATION");
        assertThat(found.get(0).severity()).isEqualTo("CRITICAL");
        assertThat(found.get(0).payloadJson()).contains("\"expected\":12.63");
    }

    @Test
    void noAnomalyIsAResultNotAFailure() {
        // Zero anomalie est le cas nominal la plupart du temps. Le traiter comme une erreur ferait
        // journaliser un avertissement 288 fois par jour.
        server.expect(requestTo("http://ai:8001/anomaly/detect"))
                .andRespond(withSuccess(
                        "{\"window_hours\":336,\"categories\":4,\"anomalies\":[]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.detect(1)).isEmpty();
        server.verify();
    }

    @Test
    void anUnreachableDetectorIsUnavailableNotAnEmptyResult() {
        server.expect(requestTo("http://ai:8001/anomaly/detect")).andRespond(withServerError());

        assertThatThrownBy(() -> client.detect(1))
                .isInstanceOf(AiServiceException.class)
                .extracting(e -> ((AiServiceException) e).status())
                .isEqualTo(503);
    }
}
