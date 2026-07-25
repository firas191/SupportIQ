package com.supportiq.backend.dashboard;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lectures d'agregation du dashboard (S4-J1).
 *
 * <p>Choix : **JdbcTemplate sur les vues SQL** plutot que JPA. Ce sont des agregats en lecture seule
 * sans identite ni cycle de vie — les mapper en entites serait de la ceremonie inutile (et Hibernate
 * `ddl-auto=validate` n'a pas a connaitre ces vues). Le SQL reste lisible et proche des vues V5.
 */
@Repository
public class DashboardRepository {

    private final JdbcTemplate jdbc;

    public DashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** KPIs globaux (vue v_ticket_stats : une seule ligne). */
    public KpiResponse kpis() {
        return jdbc.queryForObject("""
                SELECT total_tickets, new_tickets, resolved_tickets, analyzed_tickets,
                       high_priority, negative_sentiment, escalated_to_llm, avg_confidence
                FROM v_ticket_stats
                """, (rs, rowNum) -> {
            long total = rs.getLong("total_tickets");
            long analyzed = rs.getLong("analyzed_tickets");
            long high = rs.getLong("high_priority");
            long negative = rs.getLong("negative_sentiment");
            long escalated = rs.getLong("escalated_to_llm");
            return new KpiResponse(
                    total,
                    rs.getLong("new_tickets"),
                    rs.getLong("resolved_tickets"),
                    analyzed,
                    high,
                    negative,
                    escalated,
                    rate(high, analyzed),
                    rate(negative, analyzed),
                    rate(escalated, analyzed),
                    round(rs.getDouble("avg_confidence")));
        });
    }

    /** Evolution quotidienne par categorie, bornee a une fenetre de N jours. */
    public List<TrendsResponse.CategoryTrendPoint> dailyTrends(int days) {
        return jdbc.query("""
                SELECT day, category, ticket_count
                FROM v_category_trends
                WHERE day >= current_date - CAST(? AS integer)
                ORDER BY day, category
                """,
                (rs, rowNum) -> new TrendsResponse.CategoryTrendPoint(
                        rs.getObject("day", LocalDate.class),
                        rs.getString("category"),
                        rs.getLong("ticket_count")),
                days);
    }

    /** Repartition par colonne d'analyse (category / sentiment / priority). */
    public List<TrendsResponse.CountByLabel> countByAnalysisField(String field, int days) {
        // `field` n'est JAMAIS une entree utilisateur : whiteliste dans le service (anti-injection).
        String sql = """
                SELECT a.%s AS label, COUNT(*) AS c
                FROM analyses a
                JOIN tickets t ON t.id = a.ticket_id
                WHERE t.created_at >= now() - make_interval(days => CAST(? AS integer))
                GROUP BY 1 ORDER BY c DESC
                """.formatted(field);
        return jdbc.query(sql,
                (rs, rowNum) -> new TrendsResponse.CountByLabel(rs.getString("label"), rs.getLong("c")),
                days);
    }

    /** Charge horaire (heatmap 0-23). */
    public List<TrendsResponse.HourlyPoint> hourlyLoad() {
        return jdbc.query("SELECT hour_of_day, ticket_count FROM v_hourly_load ORDER BY hour_of_day",
                (rs, rowNum) -> new TrendsResponse.HourlyPoint(
                        rs.getInt("hour_of_day"), rs.getLong("ticket_count")));
    }

    private static double rate(long part, long total) {
        return total == 0 ? 0.0 : round(100.0 * part / total);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
