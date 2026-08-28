-- Repartition realiste des statuts sur le corpus de charge (S7-J5).
--
--   Get-Content perf/seed_status.sql | docker compose exec -T postgres psql -U supportiq -d supportiq
--
-- POURQUOI CE SCRIPT EXISTE
--
-- La premiere execution de `perf/plans.sql` a donne deux plans identiques avec et sans l'index
-- `ix_tickets_status_created` (V18). Ce n'etait pas la preuve que l'index est inutile : c'etait la
-- preuve que la mesure ne valait rien. Les 63 057 tickets etaient tous au statut NEW, puisque rien
-- n'est jamais resolu dans ce projet. Un filtre qui selectionne 100 % d'une table n'a evidemment
-- pas besoin d'index, et le planificateur a raison de parcourir la table.
--
-- La regle ecrite dans l'en-tete de V18 -- « si le plan ne change pas, retirer l'index » --
-- supposait un corpus representatif. L'appliquer mecaniquement sur des donnees degenerees serait
-- aussi malhonnete que de l'ignorer. On rend donc les donnees representatives, puis on remesure.
--
-- EFFET DE BORD UTILE : `resolved_at` est enfin rempli quelque part. C'etait la colonne ajoutee au
-- S7-J3 « meme si aucun ticket n'est resolu », precisement pour que la verite terrain du
-- depassement de SLA devienne un jour calculable. Elle le devient ici -- sur des donnees simulees,
-- ce qui reste a dire tel quel.
--
-- N'AFFECTE QUE LE CORPUS 'PERF-'. Les tickets recents ('VAR-', webhook, documents) gardent leur
-- statut : ils alimentent les ecrans de la semaine 7, qui regardent les dernieres 24 h a 14 jours.

\timing on

-- 75 % resolus, 10 % en cours, 15 % nouveaux.
--
-- Cette repartition est celle d'une file de support qui fonctionne : le gros du volume est traite,
-- une petite part est en cours, une petite part attend. C'est une convention plausible, pas une
-- mesure -- et c'est exactement ce qu'il faut pour que le filtre par statut redevienne selectif.
UPDATE tickets
SET status = CASE
        WHEN id % 20 < 15 THEN 'RESOLVED'
        WHEN id % 20 < 17 THEN 'IN_PROGRESS'
        ELSE 'NEW'
    END
WHERE external_ref LIKE 'PERF-%';

-- Instant de resolution : entre 1 h et ~4 jours apres l'arrivee, module par l'identifiant pour
-- rester deterministe. Une partie depasse donc l'echeance SLA et une autre non, ce qui donne enfin
-- un label exploitable.
--
-- `WHERE resolved_at IS NULL` : on n'ecrase jamais une resolution deja enregistree. Meme
-- precaution que dans `SlaRepository.applyDueDate` -- un historique qui se reecrit tout seul ne se
-- mesure plus.
-- `::int` obligatoire : `id % 96` est un bigint, `make_interval` n'accepte que des int. Sans le
-- cast, PostgreSQL ne trouve aucune signature et l'UPDATE echoue -- silencieusement du point de vue
-- du script, qui poursuit et divise ensuite par zero.
UPDATE tickets
SET resolved_at = created_at + make_interval(hours => (1 + (id % 96))::int)
WHERE external_ref LIKE 'PERF-%'
  AND status = 'RESOLVED'
  AND resolved_at IS NULL;

-- VACUUM et pas seulement ANALYZE.
--
-- Un UPDATE de 50 000 lignes cree 50 000 nouvelles versions sans liberer les anciennes : la table
-- occupe alors deux fois plus de pages, et le planificateur change de plan pour de mauvaises
-- raisons. Constate ici : la recherche plein texte est passee d'un Bitmap Index Scan a 7,5 ms a un
-- Seq Scan a 17,3 ms, uniquement a cause du gonflement.
--
-- Mesurer juste apres une modification en masse, sans VACUUM, c'est mesurer le desordre laisse par
-- la preparation des donnees.
VACUUM ANALYZE tickets;

\echo ''
\echo 'Repartition obtenue :'
SELECT status, COUNT(*) AS tickets,
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS pourcentage
FROM tickets
GROUP BY status
ORDER BY tickets DESC;

\echo ''
\echo 'Verite terrain du depassement de SLA, desormais calculable :'
SELECT COUNT(*) FILTER (WHERE resolved_at > sla_due_at) AS depasses,
       COUNT(*) FILTER (WHERE resolved_at <= sla_due_at) AS dans_les_temps,
       ROUND(100.0 * COUNT(*) FILTER (WHERE resolved_at > sla_due_at) / COUNT(*), 1) AS taux_pct
FROM tickets
WHERE resolved_at IS NOT NULL;
