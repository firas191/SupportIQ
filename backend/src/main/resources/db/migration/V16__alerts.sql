-- V16 — Alertes d'anomalie et série horaire de volume (S7-J2, rapport §4 et §9).
--
-- **Une alerte est un objet métier, pas une notification.** Elle a une durée de vie : elle naît
-- d'une mesure, elle est vue, elle est acquittée par quelqu'un. C'est ce qui la distingue d'un
-- message poussé en WebSocket, qui est un signal éphémère — et c'est pourquoi elle est écrite par
-- Spring (plan de contrôle, RBAC, identité de l'utilisateur) et non par le service IA, contrairement
-- à `analyses`, `embeddings` ou `topics`, qui ne portent aucune décision humaine.

CREATE TABLE alerts (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- VOLUME_ANOMALY aujourd'hui ; EMERGING_TOPIC et SLA_RISK sont prévus (§4) et arrivent au J3.
    type            VARCHAR(32)  NOT NULL,
    severity        VARCHAR(16)  NOT NULL,

    -- Écart assumé par rapport au §4, qui ne prévoyait que `payload jsonb`.
    --
    -- `scope` (la catégorie concernée) et `bucket_start` (l'heure sur laquelle porte la mesure)
    -- sont **l'identité** de l'anomalie. Les laisser dans `payload` obligerait à dédupliquer en
    -- fouillant du jsonb, alors que la déduplication est précisément ce qui empêche la
    -- fonctionnalité de devenir insupportable : un détecteur qui tourne toutes les dix minutes
    -- redécouvre le même pic à chaque passage, et une pile de quarante alertes identiques est
    -- exactement ce qui apprend à un responsable à ne plus les lire.
    scope           VARCHAR(64)  NOT NULL,
    bucket_start    TIMESTAMPTZ  NOT NULL,

    -- Les chiffres de la mesure : observé, attendu, score robuste, méthode employée. En jsonb parce
    -- qu'ils dépendront du type d'alerte — un risque SLA n'a pas de z-score.
    payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,

    -- Qui a acquitté, et quand. `NULL` = personne ne l'a encore prise en charge.
    acknowledged_by BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    acknowledged_at TIMESTAMPTZ,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_alerts_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    -- Même mécanisme qu'au digest (V12) : la contrainte porte **à la fois** l'idempotence du
    -- détecteur et la sûreté multi-instance. Deux nœuds qui détectent le même pic au même instant
    -- arrivent tous les deux à l'insertion ; la base en laisse passer un. Vérifier « existe-t-elle
    -- déjà ? » avant d'insérer ne suffirait pas, les deux peuvent lire « non » simultanément.
    CONSTRAINT uq_alerts_occurrence UNIQUE (type, scope, bucket_start)
);

-- Lecture principale : « qu'est-ce qui n'a pas encore été traité ? ». Index partiel — les alertes
-- acquittées s'accumulent et n'ont aucune raison d'alourdir cette recherche.
CREATE INDEX ix_alerts_open ON alerts (created_at DESC)
    WHERE acknowledged_at IS NULL;

CREATE INDEX ix_alerts_recent ON alerts (created_at DESC);

COMMENT ON TABLE alerts IS
    'Alertes detectees automatiquement, avec acquittement humain. Unicite par (type, scope, heure).';

-- ---------------------------------------------------------------------------
-- Série horaire de volume
-- ---------------------------------------------------------------------------
--
-- **Pourquoi l'heure et non le jour.** La décomposition saisonnière a besoin d'une saisonnalité à
-- retirer. Au pas horaire, elle est massive et régulière : les nuits sont vides, les matinées
-- chargées. Un z-score calculé sans la retirer déclencherait tous les jours à 9 h — c'est-à-dire
-- une alerte qui n'apprend rien, donc une alerte qu'on désactive au bout d'une semaine.
--
-- Au pas journalier, la saisonnalité est hebdomadaire (période 7) et il faudrait plusieurs mois
-- d'historique pour l'estimer. Le pas horaire donne 336 points sur deux semaines : assez pour
-- estimer une période de 24, et assez réactif pour qu'un pic soit détecté dans l'heure.
--
-- Contrepartie assumée : l'effet « jour de la semaine » n'est pas retiré ; il est en partie absorbé
-- par la tendance de la décomposition.
--
-- **Attention à la lecture** : cette vue ne contient **pas** les heures sans ticket. Une heure vide
-- est une absence de ligne, pas une ligne à zéro. Le détecteur reconstruit la grille complète — sans
-- cela, une catégorie calme aurait une moyenne calculée uniquement sur ses heures actives, et
-- paraîtrait bien plus régulière qu'elle ne l'est.

CREATE VIEW v_hourly_volume AS
SELECT
    date_trunc('hour', t.created_at)     AS bucket,
    coalesce(a.category, 'NON_ANALYSE')  AS category,
    COUNT(*)                             AS tickets
FROM tickets t
LEFT JOIN analyses a ON a.ticket_id = t.id
WHERE t.merged_into_id IS NULL
GROUP BY 1, 2;

COMMENT ON VIEW v_hourly_volume IS
    'Volume de tickets par heure et par categorie. Les heures sans ticket sont absentes.';

-- Le détecteur lit sous `insight_ro` : c'est un travail qui ne fait que lire, il n'a aucune raison
-- de disposer d'un accès en écriture. Le moindre privilège ne vaut que s'il s'applique aussi aux
-- jobs internes, quand personne ne regarde.
--
-- **Ce GRANT n'ajoute pas la vue à la surface de l'agent Insight.** Les deux listes sont
-- indépendantes par construction : le GRANT dit ce que le *rôle* peut lire, la liste blanche de
-- `sql_guard` dit ce que l'*agent* a le droit de demander. Élargir la seconde changerait le
-- comportement du text-to-SQL et invaliderait la suite d'évaluation des 30 questions (S6-J2) — un
-- prix qu'on ne paie pas pour une vue dont l'agent n'a pas besoin.
GRANT SELECT ON v_hourly_volume TO insight_ro;
