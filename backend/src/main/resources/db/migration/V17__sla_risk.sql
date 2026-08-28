-- V17 — Échéance SLA, résolution, et score de risque (S7-J3, rapport §9).
--
-- **La colonne `sla_due_at` existe depuis la V2 et n'a jamais été remplie.** Elle était le seul
-- endroit du schéma où une donnée était prévue puis oubliée — et c'est précisément celle dont ce
-- jour a besoin : sans échéance, « risque de dépassement » n'a pas de référent.
--
-- Trois ajouts, chacun pour une raison distincte.

-- ---------------------------------------------------------------------------
-- 1. `resolved_at` — la vérité terrain qui manque
-- ---------------------------------------------------------------------------
--
-- Le statut `RESOLVED` dit *qu'un* ticket a été résolu ; il ne dit pas **quand**. Sans cet
-- horodatage, on ne peut pas savoir si la résolution est intervenue avant ou après l'échéance,
-- donc on ne peut pas construire de label « dépassement » — donc on ne peut pas évaluer un modèle
-- de risque sur des données réelles, ni aujourd'hui ni jamais.
--
-- La colonne est ajoutée maintenant même si aucun ticket n'est encore résolu : c'est le jour où
-- l'on commence à accumuler l'historique qui rendra la mesure honnête possible. Une donnée de
-- vérité terrain qu'on n'enregistre pas est une mesure qu'on s'interdit pour toujours.

ALTER TABLE tickets ADD COLUMN resolved_at TIMESTAMPTZ;

COMMENT ON COLUMN tickets.resolved_at IS
    'Instant de resolution. Vide tant que le ticket est ouvert. Avec sla_due_at, c''est la verite '
    'terrain du depassement de SLA.';

-- ---------------------------------------------------------------------------
-- 2. Backfill de `sla_due_at`
-- ---------------------------------------------------------------------------
--
-- La politique est simple et volontairement lisible : le délai dépend de la priorité détectée.
-- HIGH 4 h, MEDIUM 24 h, LOW 72 h, priorité inconnue 24 h (on traite l'inconnu comme du courant —
-- lui donner 4 h ferait passer 10 000 tickets non analysés en urgence le jour du déploiement).
--
-- Écrite ici en SQL **et** dans `SlaPolicy` côté Java, ce qui est une duplication assumée : la
-- migration doit rattraper l'existant sans dépendre de l'application, et l'application doit poser
-- l'échéance des tickets à venir sans repasser par une migration. Les deux sont testées, et la
-- politique tient en trois valeurs — l'abstraction partagée coûterait plus qu'elle ne rapporte.

UPDATE tickets t
SET sla_due_at = t.created_at + (
        CASE coalesce(a.priority, 'MEDIUM')
            WHEN 'HIGH'   THEN INTERVAL '4 hours'
            WHEN 'LOW'    THEN INTERVAL '72 hours'
            ELSE               INTERVAL '24 hours'
        END)
FROM (SELECT ticket_id, priority FROM analyses) a
WHERE a.ticket_id = t.id AND t.sla_due_at IS NULL;

-- Les tickets sans analyse : même politique, branche « inconnu ».
UPDATE tickets
SET sla_due_at = created_at + INTERVAL '24 hours'
WHERE sla_due_at IS NULL;

-- « Quels tickets ouverts arrivent à échéance ? » est la requête de la file de travail. Index
-- partiel : les tickets résolus n'ont plus d'échéance à surveiller et n'ont rien à faire ici.
CREATE INDEX ix_tickets_sla_open ON tickets (sla_due_at)
    WHERE resolved_at IS NULL AND status <> 'MERGED';

-- ---------------------------------------------------------------------------
-- 3. `sla_risks` — le score, dans sa propre table
-- ---------------------------------------------------------------------------
--
-- **Pourquoi pas une colonne de `tickets`.** Le score est produit par le service IA, et depuis la
-- semaine 3 celui-ci n'écrit que dans ses propres tables (`analyses`, `embeddings`, `kb_documents`,
-- `topics`). Lui ouvrir une colonne de la table métier centrale casserait une frontière tenue
-- depuis quatre semaines pour économiser une jointure qui existe déjà dans la requête de liste.
--
-- `model` est stocké avec le score pour la même raison qu'au S5-J5 (`judged_by`) et au S7-J2
-- (`method`) : un chiffre obtenu par le repli de règles ne se compare pas à un chiffre obtenu par
-- le modèle entraîné, et rien d'autre ne permettrait de les distinguer après coup.

CREATE TABLE sla_risks (
    ticket_id   BIGINT       PRIMARY KEY REFERENCES tickets (id) ON DELETE CASCADE,
    -- Probabilité de dépassement, dans [0, 1]. NUMERIC et non float : c'est une valeur affichée
    -- et triée, la représentation exacte vaut mieux que le dernier bit de performance.
    risk        NUMERIC(4,3) NOT NULL,
    -- `lightgbm` ou `rules` : traçabilité de la provenance du chiffre.
    model       VARCHAR(32)  NOT NULL,
    computed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_sla_risk_range CHECK (risk >= 0 AND risk <= 1)
);

-- Tri « les plus à risque d'abord » sur la file de travail.
CREATE INDEX ix_sla_risks_desc ON sla_risks (risk DESC);

COMMENT ON TABLE sla_risks IS
    'Probabilite de depassement de SLA par ticket ouvert. Ecrite par le service IA, lue par Spring.';

-- Le score est **daté** parce qu'il vieillit : le temps restant avant échéance est sa variable
-- dominante, donc un score calculé il y a deux heures sous-estime le risque d'aujourd'hui.
-- L'interface affiche cette date plutôt que de laisser croire à une valeur instantanée.
