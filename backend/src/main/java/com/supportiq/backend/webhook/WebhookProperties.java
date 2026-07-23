package com.supportiq.backend.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametres du webhook d'ingestion (S2-J4). La cle API identifie l'integration ; le secret
 * signe le corps (HMAC). Le rate limit est un token bucket par cle API.
 * En prod : une cle/secret par integration (table dediee) plutot qu'une valeur globale.
 */
@ConfigurationProperties(prefix = "app.webhook")
public record WebhookProperties(String apiKey, String secret, RateLimit rateLimit) {

    /** Configuration du seau a jetons : {@code capacity} jetons, rechargés de {@code refillTokens} par {@code refillPeriod}. */
    public record RateLimit(long capacity, long refillTokens, Duration refillPeriod) {
    }
}
