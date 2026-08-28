package com.supportiq.backend.dashboard;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service du dashboard (S4-J1). Les resultats sont **caches 60 s** (Caffeine) : un dashboard est
 * consulte par plusieurs utilisateurs qui rechargent souvent, et une fraicheur a la minute est
 * largement suffisante pour des agregats — cela tient l'objectif « API dashboard < 100 ms ».
 */
@Service
public class DashboardService {

    private static final int MAX_DAYS = 365;
    private static final int DEFAULT_DAYS = 30;

    private final DashboardRepository repository;

    public DashboardService(DashboardRepository repository) {
        this.repository = repository;
    }

    @Cacheable("dashboard-kpis")
    @Transactional(readOnly = true)
    public KpiResponse kpis() {
        return repository.kpis();
    }

    @Cacheable(value = "dashboard-trends", key = "#days")
    @Transactional(readOnly = true)
    public TrendsResponse trends(int days) {
        int window = clampDays(days);
        return new TrendsResponse(
                repository.dailyTrends(window),
                // Champs whitelistes en dur : jamais de valeur utilisateur dans le SQL (anti-injection).
                repository.countByAnalysisField("category", window),
                repository.countByAnalysisField("sentiment", window),
                repository.countByAnalysisField("priority", window),
                repository.hourlyLoad());
    }

    // `alerts()` renvoyait `[]` depuis le S4-J1, en attendant la Semaine 7. Les detecteurs et la
    // table existent maintenant : les alertes vivent dans le module `alerts`, avec leur
    // acquittement (S7-J2). Le bouchon est retire plutot que laisse a cote du vrai — deux chemins
    // pour la meme information, dont un qui ment, est le meilleur moyen d'en consommer le mauvais.

    private int clampDays(int days) {
        if (days <= 0) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }
}
