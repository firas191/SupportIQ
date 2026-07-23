package com.supportiq.backend.webhook;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rate limiting du webhook via Bucket4j : un token bucket par cle API (fallback IP si absente).
 * S'execute avant le controleur ; l'exception levée est traduite en 429 par le GlobalExceptionHandler.
 *
 * <p>Buckets en memoire (suffisant en mono-instance). En prod multi-instance : backend distribue
 * (Redis/Hazelcast) pour un quota global, et une bande passante par integration stockee en base.
 */
@Component
public class WebhookRateLimitInterceptor implements HandlerInterceptor {

    private final WebhookProperties properties;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public WebhookRateLimitInterceptor(WebhookProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Bucket bucket = buckets.computeIfAbsent(clientKey(request), key -> newBucket());
        if (bucket.tryConsume(1)) {
            return true;
        }
        throw new WebhookRateLimitException("Quota d'ingestion depasse, reessayez plus tard.");
    }

    private String clientKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-Api-Key");
        return (apiKey != null && !apiKey.isBlank()) ? "key:" + apiKey : "ip:" + request.getRemoteAddr();
    }

    private Bucket newBucket() {
        WebhookProperties.RateLimit rl = properties.rateLimit();
        Bandwidth limit = Bandwidth.builder()
                .capacity(rl.capacity())
                .refillGreedy(rl.refillTokens(), rl.refillPeriod())
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
