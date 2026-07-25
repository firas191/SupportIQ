package com.supportiq.backend.dashboard;

import java.time.LocalDate;
import java.util.List;

/**
 * Tendances du dashboard (S4-J1) : evolution quotidienne par categorie, repartitions et charge
 * horaire. Un seul appel alimente les trois graphiques Chart.js du J2 (evite 3 allers-retours).
 */
public record TrendsResponse(
        List<CategoryTrendPoint> daily,
        List<CountByLabel> byCategory,
        List<CountByLabel> bySentiment,
        List<CountByLabel> byPriority,
        List<HourlyPoint> hourly) {

    /** Volume d'une categorie pour un jour donne (courbe d'evolution). */
    public record CategoryTrendPoint(LocalDate day, String category, long count) {
    }

    /** Repartition simple label -> volume (camemberts / barres). */
    public record CountByLabel(String label, long count) {
    }

    /** Charge par heure de la journee (heatmap horaire). */
    public record HourlyPoint(int hour, long count) {
    }
}
