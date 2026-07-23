-- V3 — Analyses IA du triage (rapport §4). Une analyse par ticket, écrite par le service FastAPI.
-- Pas d'entité JPA côté Spring au J3 (validate ignore les tables non mappées) ; le backend la lira
-- pour le dashboard/fiche ticket en Semaine 4.

CREATE TABLE analyses (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id        BIGINT       NOT NULL,
    priority         VARCHAR(8)   NOT NULL,
    category         VARCHAR(16)  NOT NULL,
    sentiment        VARCHAR(8)   NOT NULL,
    keywords         TEXT[]       NOT NULL DEFAULT '{}',
    confidence       NUMERIC(4,3),
    model_used       VARCHAR(32),
    latency_ms       INTEGER,
    escalated_to_llm BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_analyses_ticket    FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE,
    CONSTRAINT uq_analyses_ticket    UNIQUE (ticket_id),                  -- une analyse par ticket (idempotence)
    CONSTRAINT ck_analyses_priority  CHECK (priority  IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT ck_analyses_category  CHECK (category  IN ('TECHNIQUE','FACTURATION','COMPTE','RECLAMATION','DEMANDE')),
    CONSTRAINT ck_analyses_sentiment CHECK (sentiment IN ('NEG','NEU','POS'))
);

-- Index pour le dashboard S4 : « répartition par catégorie dans le temps ».
CREATE INDEX ix_analyses_category ON analyses (category, created_at);
