package com.supportiq.backend.common.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametres JWT (prefix app.security.jwt). Le secret doit faire >= 32 octets (HS256).
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        String issuer,
        Duration accessTtl,
        Duration refreshTtl,
        String secret) {
}
