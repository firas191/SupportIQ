package com.supportiq.backend.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Authentifie un appel webhook : cle API attendue + signature HMAC-SHA256 du corps brut.
 *
 * <p>Pourquoi HMAC en plus de la cle API : la cle seule fuit facilement (logs, proxys). La signature
 * prouve que l'appelant detient le secret partage <em>sans le transmettre</em> et lie la signature au
 * corps exact (protection contre l'alteration et le rejeu d'un corps modifie). Comparaisons en temps
 * constant ({@link MessageDigest#isEqual}) pour ne pas fuiter d'information par timing.
 */
@Component
public class WebhookSignatureVerifier {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final WebhookProperties properties;

    public WebhookSignatureVerifier(WebhookProperties properties) {
        this.properties = properties;
    }

    /** Leve {@link WebhookAuthException} si la cle API ou la signature est invalide. */
    public void verify(String apiKey, String signatureHeader, byte[] body) {
        if (!constantTimeEquals(properties.apiKey(), apiKey)) {
            throw new WebhookAuthException("Cle API absente ou invalide.");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new WebhookAuthException("En-tete de signature (X-Signature) absent.");
        }
        String provided = stripPrefix(signatureHeader.trim());
        String expected = hmacHex(body);
        if (!constantTimeEquals(expected, provided.toLowerCase())) {
            throw new WebhookAuthException("Signature HMAC invalide.");
        }
    }

    private String hmacHex(byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            // Mauvaise config du secret cote serveur : ne pas divulguer, refuser l'appel.
            throw new WebhookAuthException("Verification de signature impossible.");
        }
    }

    private static String stripPrefix(String signature) {
        return signature.startsWith(SIGNATURE_PREFIX) ? signature.substring(SIGNATURE_PREFIX.length()) : signature;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
