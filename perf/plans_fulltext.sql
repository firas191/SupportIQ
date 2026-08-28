-- Pourquoi la recherche plein texte tient 573 ms au P95 (S7-J5).
--
--   Get-Content perf/plans_fulltext.sql | docker compose exec -T postgres psql -U supportiq -d supportiq
--
-- CE QUE CE SCRIPT DEMONTRE
--
-- Au S4-J3, la recherche plein texte a ete « verifiee » avec un EXPLAIN ANALYZE montrant un
-- `Bitmap Index Scan on ix_tickets_search_vector` a 0,217 ms. Ce plan etait reel, mais il portait
-- sur une requete que l'application n'execute pas : la configuration linguistique y etait une
-- **constante** ('french'), alors que le code la choisit **par ligne** avec un CASE sur
-- `t.language`.
--
-- La difference n'est pas cosmetique. Un index se parcourt avec une cle ; si le cote droit du `@@`
-- depend de la ligne en cours, il n'y a pas de cle, et PostgreSQL n'a d'autre choix que de lire
-- toute la table en appelant `websearch_to_tsquery` a chaque ligne.
--
-- Le tir k6 l'a rendu visible (P95 573 ms contre un objectif de 300 ms). Une requete unique a
-- 300 ms passe inapercue ; c'est la charge qui transforme un defaut de plan en depassement.
--
-- LA CORRECTION TESTEE ICI : deux branches a configuration constante, reunies par OR. Chacune peut
-- alors etre servie par l'index, et PostgreSQL les combine en `BitmapOr`. La semantique est
-- identique -- un ticket anglais est interroge en anglais, tous les autres en francais -- mais elle
-- est exprimee d'une facon que l'index sait servir.

\timing on

\echo ''
\echo '================================================================'
\echo ' A. LA REQUETE DE L APPLICATION (configuration choisie par ligne)'
\echo '    Attendu : Seq Scan, et 63 057 appels a websearch_to_tsquery.'
\echo '================================================================'

EXPLAIN (ANALYZE, BUFFERS)
SELECT t.id,
       ts_rank(t.search_vector, websearch_to_tsquery(
           CASE WHEN t.language = 'en' THEN 'english'::regconfig ELSE 'french'::regconfig END,
           'remboursement')) AS rank
FROM tickets t
WHERE t.search_vector @@ websearch_to_tsquery(
        CASE WHEN t.language = 'en' THEN 'english'::regconfig ELSE 'french'::regconfig END,
        'remboursement')
ORDER BY rank DESC, t.created_at DESC
LIMIT 20 OFFSET 0;

\echo ''
\echo '================================================================'
\echo ' B. LA MEME EN DEUX BRANCHES CONSTANTES'
\echo '    Attendu : BitmapOr de deux Bitmap Index Scan sur le GIN.'
\echo ''
\echo '    IS DISTINCT FROM et non <> : `language` est nullable, et `NULL <> ''en''` vaut NULL,'
\echo '    donc un ticket sans langue detectee disparaitrait silencieusement des resultats.'
\echo '================================================================'

EXPLAIN (ANALYZE, BUFFERS)
SELECT t.id,
       ts_rank(t.search_vector, websearch_to_tsquery(
           CASE WHEN t.language = 'en' THEN 'english'::regconfig ELSE 'french'::regconfig END,
           'remboursement')) AS rank
FROM tickets t
WHERE (t.language = 'en'
       AND t.search_vector @@ websearch_to_tsquery('english'::regconfig, 'remboursement'))
   OR (t.language IS DISTINCT FROM 'en'
       AND t.search_vector @@ websearch_to_tsquery('french'::regconfig, 'remboursement'))
ORDER BY rank DESC, t.created_at DESC
LIMIT 20 OFFSET 0;

\echo ''
\echo '================================================================'
\echo ' C. CONTROLE D EQUIVALENCE'
\echo '    Les deux formes doivent renvoyer le MEME nombre de lignes.'
\echo '    Une optimisation qui change le resultat n en est pas une.'
\echo '================================================================'

SELECT
    (SELECT COUNT(*) FROM tickets t
     WHERE t.search_vector @@ websearch_to_tsquery(
             CASE WHEN t.language = 'en' THEN 'english'::regconfig ELSE 'french'::regconfig END,
             'remboursement')) AS forme_actuelle,
    (SELECT COUNT(*) FROM tickets t
     WHERE (t.language = 'en'
            AND t.search_vector @@ websearch_to_tsquery('english'::regconfig, 'remboursement'))
        OR (t.language IS DISTINCT FROM 'en'
            AND t.search_vector @@ websearch_to_tsquery('french'::regconfig, 'remboursement')))
        AS forme_proposee;

\echo ''
\echo '================================================================'
\echo ' D. MEME CONTROLE SUR UN TERME ANGLAIS'
\echo '    Le mot doit atteindre les tickets anglais via la branche english.'
\echo '================================================================'

SELECT
    (SELECT COUNT(*) FROM tickets t
     WHERE t.search_vector @@ websearch_to_tsquery(
             CASE WHEN t.language = 'en' THEN 'english'::regconfig ELSE 'french'::regconfig END,
             'refunds')) AS forme_actuelle,
    (SELECT COUNT(*) FROM tickets t
     WHERE (t.language = 'en'
            AND t.search_vector @@ websearch_to_tsquery('english'::regconfig, 'refunds'))
        OR (t.language IS DISTINCT FROM 'en'
            AND t.search_vector @@ websearch_to_tsquery('french'::regconfig, 'refunds')))
        AS forme_proposee;

\echo ''
\echo 'Repartition des langues (pour lire les plans ci-dessus) :'
SELECT COALESCE(language, '(null)') AS langue, COUNT(*) AS tickets
FROM tickets GROUP BY 1 ORDER BY 2 DESC;
