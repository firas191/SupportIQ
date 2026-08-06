-- V9 — Brouillons de réponse générés par l'agent Résolution (S5-J3, rapport §4 et §5.2).
--
-- Un brouillon n'est JAMAIS envoyé automatiquement : il est proposé à un agent humain qui le
-- valide, le corrige ou le rejette. Le statut porte cette boucle de validation, et c'est
-- l'argument central de responsible AI du projet (rapport §5.2) — la plateforme rédige, l'humain
-- décide.
--
-- Comme `analyses`, `embeddings` et `kb_documents` : table **créée par Flyway côté Spring**,
-- **écrite par FastAPI**. Pas d'entité JPA au J3 ; Spring la lira en JdbcTemplate au J4 pour le
-- panneau de la fiche ticket.

CREATE TABLE draft_responses (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id   BIGINT       NOT NULL,
    content     TEXT         NOT NULL,

    -- Citations : [{"chunk_id": 12, "source": "faq-facturation.md", "heading": "...", "marker": 1}]
    -- En jsonb et non en table de liaison : la citation n'a de sens que dans SON brouillon, on ne
    -- la requête jamais indépendamment, et sa forme évoluera (surlignage, position) au S5-J4.
    citations   JSONB        NOT NULL DEFAULT '[]'::jsonb,

    status      VARCHAR(16)  NOT NULL DEFAULT 'PROPOSED',
    tone        VARCHAR(16)  NOT NULL DEFAULT 'formal',

    -- Vrai quand l'auto-vérification n'a pas convergé après les re-générations autorisées.
    -- L'interface doit alors avertir l'agent avant qu'il ne lise le brouillon (S5-J4).
    low_confidence BOOLEAN   NOT NULL DEFAULT FALSE,
    -- Ce que l'auto-vérification a reproché à la dernière tentative — sert au débogage et à
    -- l'affichage de l'avertissement.
    issues      TEXT[]       NOT NULL DEFAULT '{}',
    attempts    SMALLINT     NOT NULL DEFAULT 1,

    judge_score NUMERIC(3,2),        -- rempli en S5-J5 (LLM-as-judge)
    reviewed_by BIGINT,              -- utilisateur ayant validé/rejeté (S5-J4)
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_drafts_ticket   FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE,
    CONSTRAINT fk_drafts_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_drafts_status   CHECK (status IN ('PROPOSED','EDITED','SENT','REJECTED')),
    CONSTRAINT ck_drafts_tone     CHECK (tone IN ('formal','empathetic')),
    CONSTRAINT ck_drafts_score    CHECK (judge_score IS NULL OR (judge_score >= 0 AND judge_score <= 1))
);

-- Le panneau de la fiche ticket demande « le dernier brouillon de CE ticket ».
-- Pas de contrainte d'unicité : on **conserve l'historique** des régénérations, comme
-- `annotations` conserve l'historique des corrections (S4-J4). Un brouillon rejeté puis regénéré
-- doit rester traçable — c'est ce qui permettra de mesurer le taux de rejet en S5-J5.
CREATE INDEX ix_drafts_ticket ON draft_responses (ticket_id, created_at DESC);

-- Tableau « Qualité IA » du rapport §5.3 : taux de brouillons validés / rejetés par période.
CREATE INDEX ix_drafts_status ON draft_responses (status, created_at);
