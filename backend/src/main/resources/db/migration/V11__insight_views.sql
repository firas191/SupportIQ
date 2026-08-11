-- V11 — Surface interrogeable de l'agent Insight et rôle en lecture seule (S6-J1, rapport §9).
--
-- L'agent Insight traduit une question de manager en SQL. Deux barrières **indépendantes** le
-- protègent, et aucune n'est censée suffire seule :
--
--   1. la validation AST côté service (sqlglot) — refuse tout ce qui n'est pas un SELECT sur les
--      vues autorisées ;
--   2. **ce fichier** — le rôle `insight_ro` n'a physiquement pas le droit de lire les tables
--      brutes ni d'écrire quoi que ce soit.
--
-- Pourquoi les deux : la première est du code, elle peut avoir un trou (sqlglot est excellent mais
-- c'est un analyseur, pas une preuve). La seconde est appliquée par PostgreSQL, elle tient même si
-- la première est contournée. Une seule barrière serait un pari sur l'exhaustivité d'une liste.

-- ---------------------------------------------------------------------------
-- 1. Vues exposées
-- ---------------------------------------------------------------------------
--
-- Décision structurante : **aucune donnée personnelle, aucun texte libre du client.**
--
-- `customer_email` et `body` sont volontairement absents de toutes les vues. Trois raisons, dans
-- l'ordre d'importance :
--   - minimisation : un chat qui peut lire les adresses clients est une fuite en attente ;
--   - injection : le corps d'un ticket est écrit par un tiers. Le faire remonter dans un résultat
--     réinjecté ensuite dans un prompt (S6-J2 : synthèse en langage naturel) ferait du client
--     l'auteur d'une partie de l'instruction ;
--   - utilité : les questions d'un manager portent sur des volumes, des tendances, des délais —
--     pas sur le contenu d'un message particulier, qui se consulte dans la fiche ticket.
--
-- `subject` est conservé : sans lui un résultat n'est qu'une liste d'identifiants, illisible.

CREATE VIEW v_tickets AS
SELECT
    t.id                                  AS ticket_id,
    t.created_at,
    t.status,
    t.source,
    t.language,
    t.sla_due_at,
    t.subject,
    (t.merged_into_id IS NOT NULL)        AS is_merged,
    a.category,
    a.priority,
    a.sentiment,
    a.confidence,
    a.escalated_to_llm,
    (a.id IS NOT NULL)                    AS is_analysed,
    -- Âge en heures : « les tickets urgents ouverts depuis plus de 48 h » est une question
    -- naturelle, et la calculer en SQL généré demanderait au modèle de manipuler des intervalles —
    -- c'est exactement le genre de détail sur lequel un text-to-SQL se trompe.
    EXTRACT(EPOCH FROM (now() - t.created_at)) / 3600 AS age_hours
FROM tickets t
LEFT JOIN analyses a ON a.ticket_id = t.id;

COMMENT ON VIEW v_tickets IS
    'Un ticket par ligne, avec son classement automatique. Sans donnee personnelle.';

-- Volume par jour et par dimension. Une vue pré-agrégée plutôt que de laisser le modèle écrire son
-- `date_trunc` : le regroupement par jour est la question posée neuf fois sur dix, et c'est là que
-- les erreurs de fuseau horaire se glissent.
CREATE VIEW v_daily_volume AS
SELECT
    date_trunc('day', t.created_at)::date  AS day,
    coalesce(a.category, 'NON_ANALYSE')    AS category,
    coalesce(a.priority, 'INCONNUE')       AS priority,
    coalesce(a.sentiment, 'INCONNU')       AS sentiment,
    t.source,
    t.language,
    COUNT(*)                               AS tickets
FROM tickets t
LEFT JOIN analyses a ON a.ticket_id = t.id
GROUP BY 1, 2, 3, 4, 5, 6;

COMMENT ON VIEW v_daily_volume IS
    'Volume de tickets par jour, categorie, priorite, humeur, canal et langue.';

-- Activité de la boucle de validation humaine (S5-J4). Répond à « combien de réponses proposées
-- ont été validées cette semaine, et par qui ». `content` et `final_content` sont exclus : ce sont
-- des textes destinés à des clients, ils n'ont rien à faire dans un résultat d'agrégation.
CREATE VIEW v_draft_activity AS
SELECT
    date_trunc('day', d.created_at)::date  AS day,
    d.status,
    d.tone,
    d.low_confidence,
    d.abstained,
    d.attempts,
    d.judge_score,
    (d.final_content IS NOT NULL)          AS was_edited,
    u.email                                AS reviewed_by,
    EXTRACT(EPOCH FROM (d.reviewed_at - d.created_at)) / 60 AS review_delay_minutes
FROM draft_responses d
LEFT JOIN users u ON u.id = d.reviewed_by;

COMMENT ON VIEW v_draft_activity IS
    'Reponses proposees et decisions humaines. Sans le texte des reponses.';

-- ---------------------------------------------------------------------------
-- 2. Rôle en lecture seule
-- ---------------------------------------------------------------------------
--
-- Le mot de passe vient d'un placeholder Flyway (`spring.flyway.placeholders.insight_password`,
-- alimenté par la variable d'environnement `INSIGHT_DB_PASSWORD`). Il n'est **jamais** écrit dans
-- ce fichier : une migration est versionnée, un secret ne l'est pas.
--
-- `CREATE ROLE` n'accepte pas `IF NOT EXISTS` : le bloc DO rend la migration rejouable sur une base
-- où le rôle existe déjà (les rôles vivent au niveau du cluster, pas de la base — recréer la base
-- ne les efface pas).

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'insight_ro') THEN
        CREATE ROLE insight_ro LOGIN PASSWORD '${insight_password}';
    ELSE
        ALTER ROLE insight_ro LOGIN PASSWORD '${insight_password}';
    END IF;
END
$$;

-- Ceinture et bretelles : même une requête qui passerait les deux premières barrières ne peut pas
-- écrire, parce que toute transaction de ce rôle démarre en lecture seule.
ALTER ROLE insight_ro SET default_transaction_read_only = on;

-- Et un plafond de temps au niveau du rôle, en plus de celui posé par le service : une requête
-- pathologique (jointure croisée sur 10 000 tickets) ne doit pas immobiliser la base.
ALTER ROLE insight_ro SET statement_timeout = '5s';

-- Le rôle part de zéro, puis on ouvre exactement ce qui est nécessaire.
--
-- Ces deux REVOKE sont des **no-op** dans une base neuve : PostgreSQL n'accorde aucun droit de
-- lecture par défaut sur les tables, ni à un rôle nouveau ni au pseudo-rôle PUBLIC. Ils sont écrits
-- quand même parce qu'une migration doit être correcte sur une base qui a vécu — si quelqu'un a
-- accordé des droits à ce rôle entre-temps, cette migration les retire.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM insight_ro;
REVOKE ALL ON SCHEMA public FROM insight_ro;

GRANT USAGE ON SCHEMA public TO insight_ro;
GRANT SELECT ON v_tickets, v_daily_volume, v_draft_activity TO insight_ro;

-- Les vues du tableau de bord (V5) sont également des agrégats sans donnée personnelle : elles
-- répondent directement aux questions les plus fréquentes, autant les rendre interrogeables.
GRANT SELECT ON v_ticket_stats, v_category_trends, v_hourly_load TO insight_ro;

-- Sans cette ligne, un futur `CREATE TABLE` dans `public` serait automatiquement lisible par
-- `insight_ro` si quelqu'un accordait des droits par défaut. On fige l'inverse : rien par défaut.
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM insight_ro;
