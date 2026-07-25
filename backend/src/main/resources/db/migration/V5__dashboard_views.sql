-- V5 — Vues d'agregation pour le dashboard (S4-J1, rapport §9).
-- Pourquoi des VUES : la logique d'agregation vit dans la base (une seule definition, reutilisable
-- par l'API et plus tard par l'agent Insight Text-to-SQL en lecture seule, rapport §5).
-- Les vues sont *non materialisees* : les donnees restent fraiches, et le cache Caffeine 60 s
-- cote application absorbe la charge (suffisant a cette echelle ; MATERIALIZED VIEW + refresh
-- periodique serait la porte de sortie si le volume explose).

-- KPIs globaux : une seule ligne, calculee sur la jointure tickets + analyses.
CREATE VIEW v_ticket_stats AS
SELECT
    COUNT(*)                                                          AS total_tickets,
    COUNT(*) FILTER (WHERE t.status = 'NEW')                          AS new_tickets,
    COUNT(*) FILTER (WHERE t.status = 'RESOLVED')                     AS resolved_tickets,
    COUNT(a.id)                                                       AS analyzed_tickets,
    COUNT(*) FILTER (WHERE a.priority = 'HIGH')                       AS high_priority,
    COUNT(*) FILTER (WHERE a.sentiment = 'NEG')                       AS negative_sentiment,
    COUNT(*) FILTER (WHERE a.escalated_to_llm)                        AS escalated_to_llm,
    AVG(a.confidence)                                                 AS avg_confidence
FROM tickets t
LEFT JOIN analyses a ON a.ticket_id = t.id;

-- Tendances : volume par jour et par categorie (alimente les courbes d'evolution).
CREATE VIEW v_category_trends AS
SELECT
    date_trunc('day', t.created_at)::date AS day,
    COALESCE(a.category, 'NON_ANALYSE')   AS category,
    COUNT(*)                              AS ticket_count
FROM tickets t
LEFT JOIN analyses a ON a.ticket_id = t.id
GROUP BY 1, 2;

-- Charge horaire : heatmap "a quelle heure arrivent les tickets" (0-23).
CREATE VIEW v_hourly_load AS
SELECT
    EXTRACT(HOUR FROM t.created_at)::int AS hour_of_day,
    COUNT(*)                             AS ticket_count
FROM tickets t
GROUP BY 1;

-- Index composite pour le dashboard (rapport §4). (status, sla_due_at) existe deja (V2).
CREATE INDEX IF NOT EXISTS ix_tickets_created_at ON tickets (created_at);
