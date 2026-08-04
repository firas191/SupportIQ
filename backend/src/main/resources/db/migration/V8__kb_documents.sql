-- V8 — Base de connaissances (S5-J1, rapport §4 et §9 Semaine 5).
--
-- Une ligne = un **chunk** (fragment sémantique d'un document), pas un document entier :
-- c'est l'unité que le retrieval renvoie et que l'agent Résolution citera en S5-J3. Découper à
-- l'indexation plutôt qu'à la recherche permet d'embedder une fois pour toutes.
--
-- Comme `analyses` et `embeddings`, la table est **créée par Flyway côté Spring** mais **écrite par
-- FastAPI** (asyncpg) : le schéma reste sous contrôle du plan de contrôle, le calcul vectoriel reste
-- au plan de calcul. Pas d'entité JPA — `ddl-auto=validate` ignore les tables non mappées ; Spring la
-- lit en JdbcTemplate pour l'écran d'administration.

CREATE TABLE kb_documents (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       VARCHAR(300) NOT NULL,          -- titre lisible du document d'origine
    source      VARCHAR(300) NOT NULL,          -- nom de fichier : identifie le document, sert de clé de ré-import
    chunk_index INTEGER      NOT NULL,          -- position du fragment dans le document (ordre de lecture)
    heading     VARCHAR(300),                   -- section d'origine du fragment (chemin des titres Markdown)
    content     TEXT         NOT NULL,
    vector      vector(768),                    -- multilingual-e5-base, comme les tickets (même espace)
    model       VARCHAR(64),                    -- modèle ayant produit le vecteur (traçabilité d'un ré-index)
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Ré-importer le même fichier remplace ses fragments au lieu de les dupliquer.
    CONSTRAINT uq_kb_source_chunk UNIQUE (source, chunk_index)
);

-- Index vectoriel : même choix qu'en V4 (HNSW cosinus), donc mêmes propriétés de recall/latence.
CREATE INDEX ix_kb_documents_vector ON kb_documents USING hnsw (vector vector_cosine_ops);

-- L'écran d'administration liste et supprime par document : l'accès par `source` doit être direct.
CREATE INDEX ix_kb_documents_source ON kb_documents (source);
