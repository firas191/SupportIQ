-- V4 — Embeddings vectoriels des tickets (rapport §4) pour la recherche de similaires (S3-J4).
-- pgvector est fourni par l'image postgres (pgvector/pgvector:pg16). Le service FastAPI écrit les
-- vecteurs (asyncpg) ; Spring ne mappe pas cette table au J4 (validate ignore les tables non mappées).

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE embeddings (
    ticket_id  BIGINT       PRIMARY KEY REFERENCES tickets (id) ON DELETE CASCADE,
    vector     vector(768)  NOT NULL,          -- intfloat/multilingual-e5-base (FR+EN), 768 dims
    model      VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Index HNSW pour une recherche de plus proches voisins rapide en distance cosinus.
CREATE INDEX ix_embeddings_hnsw ON embeddings USING hnsw (vector vector_cosine_ops);
