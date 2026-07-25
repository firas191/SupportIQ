package com.supportiq.backend.dashboard;

/**
 * Cartes KPI du dashboard (S4-J1). Les taux sont pre-calcules cote serveur : le frontend affiche,
 * il ne recalcule pas (une seule definition de la metrique = pas de divergence back/front).
 */
public record KpiResponse(
        long totalTickets,
        long newTickets,
        long resolvedTickets,
        long analyzedTickets,
        long highPriority,
        long negativeSentiment,
        long escalatedToLlm,
        double highPriorityRate,     // % des tickets analyses en priorite HAUTE
        double negativeRate,         // % des tickets analyses au sentiment NEGATIF
        double escalationRate,       // % des analyses ayant necessite une escalade LLM (metrique de cout)
        double avgConfidence) {
}
