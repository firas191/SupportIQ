package com.supportiq.backend.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Unitaire (sans Spring) : cle API + verification HMAC du corps brut. */
class WebhookSignatureVerifierTest {

    private static final String KEY = "k1";
    private static final String SECRET = "s3cr3t";

    private final WebhookProperties props = new WebhookProperties(KEY, SECRET,
            new WebhookProperties.RateLimit(60, 60, Duration.ofMinutes(1)));
    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(props);

    @Test
    void validSignature_passes() {
        byte[] body = "{\"subject\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        String sig = hmacHex(SECRET, body);
        assertThatCode(() -> verifier.verify(KEY, sig, body)).doesNotThrowAnyException();
    }

    @Test
    void sha256Prefix_isTolerated() {
        byte[] body = "{\"subject\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        String sig = "sha256=" + hmacHex(SECRET, body);
        assertThatCode(() -> verifier.verify(KEY, sig, body)).doesNotThrowAnyException();
    }

    @Test
    void wrongApiKey_throws() {
        byte[] body = "{\"subject\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifier.verify("intrus", hmacHex(SECRET, body), body))
                .isInstanceOf(WebhookAuthException.class);
    }

    @Test
    void wrongSignature_throws() {
        byte[] body = "{\"subject\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        String forged = hmacHex("mauvais-secret", body);
        assertThatThrownBy(() -> verifier.verify(KEY, forged, body))
                .isInstanceOf(WebhookAuthException.class);
    }

    @Test
    void missingSignature_throws() {
        byte[] body = "{\"subject\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifier.verify(KEY, null, body))
                .isInstanceOf(WebhookAuthException.class);
    }

    private static String hmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
