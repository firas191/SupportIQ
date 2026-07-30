-- V6 — Recherche full-text FR/EN (S4-J3, rapport §4 : index GIN sur tickets(subject, body)).
--
-- Choix : une colonne **generee STORED** plutot qu'un index d'expression, pour (a) pouvoir
-- l'interroger directement (`search_vector @@ query`), (b) la reutiliser dans le ts_rank sans
-- recalcul, (c) garder le SQL de recherche lisible.
--
-- Contrainte importante : une colonne generee exige une expression IMMUTABLE. On utilise donc la
-- forme **a deux arguments** `to_tsvector('french', ...)` — la forme a un argument depend du reglage
-- de session `default_text_search_config`, elle n'est que STABLE et serait refusee.
-- Le CASE sur la langue reste immutable (configs litterales) : chaque ticket est indexe avec la
-- bonne langue (stemming francais pour 'paiement/paiements', anglais pour 'refund/refunds').
-- `unaccent` n'est pas utilise ici : il depend d'un dictionnaire modifiable, donc non IMMUTABLE.

ALTER TABLE tickets
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        CASE WHEN language = 'en'
             THEN to_tsvector('english',
                    coalesce(subject, '') || ' ' || coalesce(body, ''))
             ELSE to_tsvector('french',
                    coalesce(subject, '') || ' ' || coalesce(body, ''))
        END
    ) STORED;

-- Index GIN : c'est lui qui rend la recherche sous-lineaire (objectif < 200 ms sur 50k tickets).
CREATE INDEX ix_tickets_search_vector ON tickets USING GIN (search_vector);

-- Fallback "flou" (fautes de frappe, recherche partielle) : trigram sur le sujet.
-- pg_trgm est cree par infra/postgres/init.sql ; IF NOT EXISTS rend la migration idempotente.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_tickets_subject_trgm ON tickets USING GIN (subject gin_trgm_ops);
