-- V14 — Journal des exécutions d'agents (S6-J5, rapport §4 et §9).
--
-- Le projet compte quatre agents qui appellent des modèles payants : triage, Résolution, Insight,
-- Digest. Jusqu'ici, la seule trace de leur activité était les journaux du conteneur — perdus au
-- redémarrage, impossibles à agréger, et muets sur le coût.
--
-- Cette table répond à trois questions qu'on ne pouvait pas poser :
--   « combien coûte réellement un ticket ? », « quelle part des exécutions a tourné en mode
--   dégradé ? », « pourquoi celle-là a-t-elle échoué ? ».
--
-- Elle est écrite par **FastAPI** (asyncpg) et créée par Flyway côté Spring, comme `analyses`,
-- `embeddings`, `kb_documents` et `draft_responses`. Même frontière depuis la semaine 3.

CREATE TABLE agent_runs (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- triage | resolution | insight | digest | judge
    agent             VARCHAR(32)  NOT NULL,
    -- Ticket concerné quand il y en a un. Insight et Digest n'en ont pas : ils portent sur
    -- l'ensemble de l'activité. Pas de clé étrangère — un run doit survivre à la suppression du
    -- ticket qu'il a traité, sinon l'historique de coût se réécrit tout seul.
    ticket_id         BIGINT,

    calls             SMALLINT     NOT NULL DEFAULT 0,
    prompt_tokens     INTEGER      NOT NULL DEFAULT 0,
    completion_tokens INTEGER      NOT NULL DEFAULT 0,
    duration_ms       INTEGER      NOT NULL DEFAULT 0,

    -- Modèle ayant réellement répondu au dernier appel. Non trivial : la chaîne de repli peut
    -- l'avoir remplacé par un autre, et un résultat obtenu en repli ne se compare pas à un résultat
    -- nominal (leçon du S5-J5 avec `judged_by`).
    model_used        VARCHAR(96),
    -- Au moins un appel n'a pas obtenu le fournisseur prévu.
    degraded          BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Message d'échec. NULL = le run est allé au bout.
    error             TEXT,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- « Combien ont coûté les agents cette semaine, et lesquels ont dégradé ? »
CREATE INDEX ix_agent_runs_recent ON agent_runs (created_at DESC, agent);
-- Diagnostic ciblé : retrouver toutes les exécutions liées à un ticket précis.
CREATE INDEX ix_agent_runs_ticket ON agent_runs (ticket_id) WHERE ticket_id IS NOT NULL;

COMMENT ON TABLE agent_runs IS
    'Journal des executions d''agents : cout en jetons, duree, modele reellement utilise, echecs.';
