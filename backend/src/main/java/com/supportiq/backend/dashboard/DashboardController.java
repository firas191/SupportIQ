package com.supportiq.backend.dashboard;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints du dashboard (S4-J1, rapport §6).
 *
 * <p>RBAC : reserve a **MANAGER et au-dessus** (rapport §7 — l'AGENT traite des tickets, le MANAGER
 * pilote). La hierarchie des roles fait qu'un ADMIN passe aussi.
 */
@RestController
@RequestMapping("/api/dashboard")
@PreAuthorize("hasRole('MANAGER')")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    /** Cartes KPI : volumes, taux de haute priorite / sentiment negatif / escalade LLM. */
    @GetMapping("/kpis")
    public KpiResponse kpis() {
        return service.kpis();
    }

    /** Series pour les graphiques : evolution quotidienne, repartitions, charge horaire. */
    @GetMapping("/trends")
    public TrendsResponse trends(@RequestParam(defaultValue = "30") int days) {
        return service.trends(days);
    }

    /** Alertes (anomalies de volume, sujets emergents, risque SLA) — implementees en Semaine 7. */
    @GetMapping("/alerts")
    public List<Object> alerts() {
        return service.alerts();
    }
}
