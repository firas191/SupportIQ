-- Plans d'execution avant / apres l'index V18 (S7-J5).
--
--   Get-Content perf/plans.sql | docker compose exec -T postgres psql -U supportiq -d supportiq
--
-- Le script mesure AVEC l'index, le retire, remesure SANS, puis le RECREE a l'identique. Il est
-- donc rejouable et laisse la base dans l'etat ou il l'a trouvee.
--
-- ATTENTION : lancer `ANALYZE tickets;` avant. Sans statistiques a jour, le planificateur croit la
-- table petite et choisit des plans que la production n'aurait jamais. C'est la premiere cause de
-- mesures incomprehensibles.

\timing on
\echo ''
\echo '================================================================'
\echo ' 1. FILE DE TRAVAIL : filtre par statut + tri par date'
\echo '    La requete dominante. AVEC ix_tickets_status_created (V18).'
\echo '================================================================'

EXPLAIN (ANALYZE, BUFFERS)
SELECT t.id, t.subject, t.status, t.created_at,
       a.priority, a.category, a.sentiment,
       r.risk, r.model, r.computed_at
FROM tickets t
LEFT JOIN analyses a ON a.ticket_id = t.id
LEFT JOIN sla_risks r ON r.ticket_id = t.id
WHERE t.status = 'NEW'
ORDER BY t.created_at DESC NULLS LAST, t.id DESC
LIMIT 20 OFFSET 0;

\echo ''
\echo '================================================================'
\echo ' 2. LA MEME, SANS l index'
\echo '================================================================'

DROP INDEX ix_tickets_status_created;

EXPLAIN (ANALYZE, BUFFERS)
SELECT t.id, t.subject, t.status, t.created_at,
       a.priority, a.category, a.sentiment,
       r.risk, r.model, r.computed_at
FROM tickets t
LEFT JOIN analyses a ON a.ticket_id = t.id
LEFT JOIN sla_risks r ON r.ticket_id = t.id
WHERE t.status = 'NEW'
ORDER BY t.created_at DESC NULLS LAST, t.id DESC
LIMIT 20 OFFSET 0;

-- Recreation a l'identique de la V18. Le script ne doit pas laisser la base amputee.
CREATE INDEX ix_tickets_status_created ON tickets (status, created_at DESC, id DESC);

\echo ''
\echo '================================================================'
\echo ' 3. COMPTAGE DE PAGINATION'
\echo '    A regarder : PostgreSQL elimine-t-il les deux jointures ?'
\echo '    Il en a le droit (analyses.ticket_id est UNIQUE, sla_risks.ticket_id est PK),'
\echo '    donc au plus une ligne jointe, et aucune de leurs colonnes n est lue.'
\echo '================================================================'

EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(*)
FROM tickets t
LEFT JOIN analyses a ON a.ticket_id = t.id
LEFT JOIN sla_risks r ON r.ticket_id = t.id
WHERE t.status = 'NEW';

\echo ''
\echo '================================================================'
\echo ' 4. RECHERCHE PLEIN TEXTE'
\echo '    Attendu : Bitmap Index Scan on ix_tickets_search_vector (S4-J3).'
\echo '================================================================'

EXPLAIN (ANALYZE, BUFFERS)
SELECT t.id, t.subject,
       ts_rank(t.search_vector, websearch_to_tsquery('french', 'remboursement')) AS rank
FROM tickets t
WHERE t.search_vector @@ websearch_to_tsquery('french', 'remboursement')
ORDER BY rank DESC
LIMIT 20;

\echo ''
\echo '================================================================'
\echo ' 5. PAGINATION PROFONDE : le cas volontairement defavorable'
\echo '    OFFSET 10000 oblige PostgreSQL a produire puis jeter 10 000 lignes.'
\echo '    Aucun index n y change rien : c est une propriete de l API, pas du schema.'
\echo '    Mesure pour documenter la limite, pas pour l optimiser.'
\echo '================================================================'

EXPLAIN (ANALYZE, BUFFERS)
SELECT t.id, t.subject
FROM tickets t
ORDER BY t.created_at DESC NULLS LAST, t.id DESC
LIMIT 20 OFFSET 10000;

\echo ''
\echo '================================================================'
\echo ' 6. JOINTURE DIFFEREE : la meme reponse, dans l autre ordre'
\echo ''
\echo '    Les sections 1 et 2 ont montre que ix_tickets_status_created n est PAS choisi.'
\echo '    Ma justification dans V18 etait fausse : je disais que le tri viendrait de l index'
\echo '    et que LIMIT 20 s arreterait apres vingt lignes. C est vrai d une requete sur la'
\echo '    seule table tickets ; ce n en est pas une. Le ORDER BY s applique APRES les deux'
\echo '    jointures, donc PostgreSQL joint TOUTES les lignes filtrees avant d en garder 20.'
\echo ''
\echo '    Ici on trie et on limite AVANT de joindre. Si ce plan utilise l index et va plus'
\echo '    vite, l index est bon et c est la requete qu il faut changer. Sinon l index part.'
\echo '================================================================'

EXPLAIN (ANALYZE, BUFFERS)
SELECT t.id, t.subject, t.status, t.created_at,
       a.priority, a.category, a.sentiment,
       r.risk, r.model, r.computed_at
FROM (
    SELECT id
    FROM tickets
    WHERE status = 'NEW'
    ORDER BY created_at DESC NULLS LAST, id DESC
    LIMIT 20 OFFSET 0
) k
JOIN tickets t ON t.id = k.id
LEFT JOIN analyses a ON a.ticket_id = t.id
LEFT JOIN sla_risks r ON r.ticket_id = t.id
ORDER BY t.created_at DESC NULLS LAST, t.id DESC;

\echo ''
\echo '================================================================'
\echo ' 7. VERIFICATION : l index est bien revenu'
\echo '================================================================'

SELECT indexname FROM pg_indexes
WHERE tablename = 'tickets' AND indexname = 'ix_tickets_status_created';
