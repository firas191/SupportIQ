package com.supportiq.backend.common;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache applicatif (S4-J1) : Caffeine, TTL 60 s.
 *
 * <p>Cible : les agregats du dashboard, couteux a recalculer et consultes en rafale. 60 s est le
 * compromis du rapport (§9) — assez frais pour un tableau de bord, assez long pour absorber les
 * rechargements. Cache **en memoire par instance** ; en multi-instance on passerait a Redis.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final Duration TTL = Duration.ofSeconds(60);

    @Bean
    public CaffeineCacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("dashboard-kpis", "dashboard-trends");
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(200)          // borne memoire : quelques fenetres de periode suffisent
                .recordStats());
        return manager;
    }
}
