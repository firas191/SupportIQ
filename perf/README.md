# Charge et robustesse — protocole (S7-J5)

> Objectif du rapport §9 : **P95 < 300 ms** sur les endpoints critiques avec 50 000 tickets en base,
> et une chaîne asynchrone qui survit à la mort du worker en plein traitement.
>
> ⚠ **Ce fichier est le protocole, pas les résultats.** Les chiffres vont dans
> `eval/results/perf_s7j5.md`, produit en exécutant ce qui suit. Un rapport de performance rédigé
> sans avoir tourné ne vaut rien — et la sandbox de développement n'a ni Docker ni k6.

---

## 0. Pourquoi mesurer avant d'optimiser

La migration `V18__perf_indexes.sql` n'ajoute **qu'un seul index**, et son en-tête explique les
quatre qui ont été écartés. La règle tenue ce jour-là : *un index qu'on n'a pas vu apparaître dans
un plan d'exécution est une hypothèse, pas une optimisation* — et il se paie à chaque écriture sur
la table la plus écrite du projet.

Le protocole ci-dessous demande donc systématiquement le plan **avant** et **après**.

---

## 1. Préparer les données

```bash
python scripts/generate_sample_csv.py 50000 samples/tickets_50k.csv
```

Le générateur produit des horodatages **réalistes** (rythme jour/nuit, week-ends creux, fenêtre
glissante se terminant aujourd'hui) et non un ticket par minute. Ce n'est pas cosmétique : sur un
flux parfaitement régulier, le détecteur d'anomalies refuse de conclure, les vues horaires sont
plates, et une mesure de charge porterait sur une distribution que rien ne produit dans la réalité.

Import via l'interface (`/imports`, compte ADMIN) ou par l'API. Puis :

```sql
-- Sans cela, le planificateur travaille sur des statistiques d'avant l'import et choisit
-- des plans absurdes. C'est la premiere cause de « mesures » incomprehensibles.
ANALYZE tickets;
ANALYZE analyses;
ANALYZE sla_risks;
```

---

## 2. Plans d'exécution — avant / après l'index V18

Sur une base **restaurée à la V17** (ou avec l'index supprimé à la main) :

```sql
DROP INDEX IF EXISTS ix_tickets_status_created;

EXPLAIN (ANALYZE, BUFFERS)
SELECT t.id, t.subject, t.created_at
FROM tickets t
LEFT JOIN analyses a ON a.ticket_id = t.id
LEFT JOIN sla_risks r ON r.ticket_id = t.id
WHERE t.status = 'NEW'
ORDER BY t.created_at DESC NULLS LAST, t.id DESC
LIMIT 20 OFFSET 0;
```

**Ce qu'on cherche dans le plan :**

| Avant | Après |
|---|---|
| `Sort` (ou `Incremental Sort`) sur plusieurs dizaines de milliers de lignes | `Index Scan Backward using ix_tickets_status_created` |
| `Sort Method: external merge` ou un `Sort Memory` élevé | plus de nœud `Sort` du tout |

Puis recréer l'index (`docker compose up -d --build --force-recreate backend`, Flyway applique V18)
et rejouer la même requête.

**Si le plan ne change pas, l'index est inutile : il faut le retirer.** C'est le seul verdict
honnête, et c'est pour cela que la mesure vient avant la conclusion.

### Lancer k6 sans rien installer, et **dans le bon réseau**

L'image officielle suffit :

```powershell
$net = docker network ls --format "{{.Name}}" | Select-String "stageproxym" | Select-Object -First 1

docker run --rm -i --network $net -v "${PWD}/perf:/perf" grafana/k6 run `
  -e BASE=http://backend:8080 -e PASSWORD=admin1234 /perf/k6/search.js
```

**`--network` n'est pas un détail de confort.** La variante évidente — `host.docker.internal` — fait
traverser au trafic le mandataire réseau de Docker Desktop côté Windows. À 188 requêtes/s pendant
trois minutes, soit ~36 000 connexions, ce mandataire finit par refuser d'en ouvrir de nouvelles :
on obtient des `dial: i/o timeout` groupés en fin de tir, qui ressemblent à une défaillance de
l'application alors qu'ils n'ont pas atteint l'application.

La signature à reconnaître : l'erreur est au niveau **`dial`** (la connexion ne s'ouvre pas), le
`http_req_duration max` reste bas (aucune requête partie n'a été lente), et les échecs sont groupés
dans le temps au lieu d'être répartis. Trois indices qui disent tous « l'injecteur, pas la cible ».

En parlant au conteneur par son nom de service, il n'y a plus de mandataire du tout.

### Ce que la mesure a effectivement donné — et pourquoi la règle ci-dessus ne s'est pas appliquée

Le plan **n'a pas changé** : l'index n'était pas choisi, ni avec 100 % de tickets `NEW`, ni après
avoir rendu la répartition des statuts réaliste. Appliquer la règle mécaniquement aurait conduit à
retirer l'index.

Ç'aurait été une erreur, et c'est le passage intéressant de la journée. Un troisième plan — les
mêmes lignes, mais **triées et limitées avant d'être jointes** — utilise l'index et va **neuf fois
plus vite** :

| Forme de la requête | Plan | Temps | Pages |
|---|---|---|---|
| jointures puis tri (celle du code) | `Seq Scan` + 2 hash joins + tri sur 20 557 lignes | 45,7 ms | 8 532 |
| la même, sans l'index | identique | 32,6 ms | 8 526 |
| **tri puis jointures** | **`Index Only Scan`, `Heap Fetches: 0`, 3 nested loops sur 20 lignes** | **3,7 ms** | **251** |

L'index n'était donc pas inutile : il était **inutilisable par une requête qui demandait le travail
dans le mauvais ordre**. Le `ORDER BY` s'appliquant après deux `LEFT JOIN`, PostgreSQL joignait les
20 557 lignes filtrées pour en garder vingt — aucun index ne peut éviter un travail explicitement
réclamé.

`TicketSearchRepository` sélectionne désormais les identifiants de la page d'abord, puis ne joint
que ces vingt lignes.

**Correction de la règle, pour les fois suivantes** : « si le plan ne change pas, retirer l'index »
suppose que la requête soit capable de s'en servir. Avant de conclure qu'un index est mort, il faut
avoir essayé au moins une **reformulation** de la requête. Sinon on ne mesure pas l'index, on mesure
la forme qu'on lui a imposée.

La règle n'a pas été affaiblie après coup pour sauver un index : elle a été appliquée jusqu'au bout
(le verdict « il part » était écrit), puis c'est la mesure de la reformulation qui l'a renversé.

### Les deux autres plans à relever

```sql
-- Recherche plein texte : doit passer par le GIN (S4-J3, deja verifie sur 10 022 tickets).
EXPLAIN ANALYZE
SELECT t.id FROM tickets t
WHERE t.search_vector @@ websearch_to_tsquery('french', 'remboursement')
ORDER BY ts_rank(t.search_vector, websearch_to_tsquery('french', 'remboursement')) DESC
LIMIT 20;

-- Pagination profonde : le cas volontairement defavorable.
EXPLAIN ANALYZE
SELECT t.id FROM tickets t
ORDER BY t.created_at DESC, t.id DESC
LIMIT 20 OFFSET 10000;
```

La seconde **ne sera pas rapide**, et c'est attendu : `OFFSET 10000` oblige PostgreSQL à produire
puis jeter dix mille lignes. Aucun index n'y change quoi que ce soit — c'est une propriété de
l'API, pas du schéma. La corriger demande une pagination par curseur
(`WHERE (created_at, id) < (?, ?)`), donc un changement de contrat. On la mesure pour savoir à
partir de quelle page elle fait mal, et on l'écrit dans le rapport.

---

## 3. Tirs k6

```bash
# https://k6.io/docs/get-started/installation/
k6 run -e BASE=http://localhost:8080 -e EMAIL=admin@supportiq.local -e PASSWORD=... perf/k6/search.js
k6 run -e BASE=http://localhost:8080 -e EMAIL=admin@supportiq.local -e PASSWORD=... perf/k6/dashboard.js
```

**Deux précautions qui changent les chiffres :**

1. **Le jeton est obtenu une seule fois** (`setup()`). Se connecter à chaque itération mesurerait
   BCrypt — coût 12, délibérément lent — et non la recherche.
2. **Montée progressive** (`ramping-vus`). Un palier brutal mesure le remplissage du pool de
   connexions et le réchauffement du cache de plans, pas le régime établi.

Sur le tableau de bord, la métrique `dashboard_cold_duration` isole le **premier** appel de chaque
VU : `kpis` et `trends` sont cachés 60 s (Caffeine, S4-J1), et sans cette distinction on publierait
un P95 de 5 ms en croyant avoir mesuré des agrégats sur 50 000 tickets.

**Reporter dans le rapport, pour chaque scénario :** P50, P95, P99, débit, taux d'erreur — et la
configuration de la machine. Un P95 sans le contexte matériel n'est comparable à rien.

---

## 4. Résilience RabbitMQ — tuer le worker en plein traitement

C'est la garantie qui compte le plus, parce qu'elle porte sur des données : *un ticket importé
est-il analysé, même si le service IA meurt au milieu du lot ?*

```bash
# 1. Lancer un import de 5 000 tickets depuis l'interface, puis immediatement :
docker compose exec rabbitmq rabbitmqctl list_queues name messages messages_unacknowledged

# 2. Tuer le worker en plein traitement (SIGKILL, pas d'arret propre) :
docker kill --signal=SIGKILL $(docker compose ps -q ai-service)

# 3. Constater que les messages non acquittes reviennent en file :
docker compose exec rabbitmq rabbitmqctl list_queues name messages messages_unacknowledged

# 4. Relancer et verifier que la file se vide sans perte :
docker compose up -d ai-service
```

**Ce qu'on doit observer, et pourquoi :**

- Les messages `unacknowledged` au moment du kill **repassent en `ready`**. C'est le comportement
  d'`aio_pika` avec `message.process()` (S2-J3) : l'acquittement n'a lieu qu'après traitement
  complet, donc une mort brutale ne peut pas perdre un message — au pire elle en fait rejouer un.
- Le total de tickets **analysés** en base doit rejoindre le total importé. La requête de contrôle :

  ```sql
  SELECT (SELECT COUNT(*) FROM tickets)  AS tickets,
         (SELECT COUNT(*) FROM analyses) AS analyses;
  ```

- Les messages rejoués **ne créent pas de doublon d'analyse** : `analyses` porte
  `UNIQUE (ticket_id)` depuis la V3, et c'est précisément à cela qu'elle sert. C'est l'idempotence
  qui rend le rejeu sûr — sans elle, « au moins une fois » deviendrait « deux analyses pour un
  ticket ».
- Une exception de traitement (et non un kill) envoie le message en **DLQ** `tickets.analyze.dlq`
  au lieu de le rejouer indéfiniment. À vérifier aussi : une boucle de rejeu infinie sur un message
  empoisonné est une panne bien pire qu'un message perdu, parce qu'elle bloque tous les suivants.

### Second scénario : couper le broker, pas le worker

```bash
docker compose stop rabbitmq
# … creer un ticket via le webhook : l'API doit repondre normalement …
docker compose start rabbitmq
```

Attendu : la création du ticket **réussit** (la publication est best-effort et postérieure au
commit, S2-J3), et le consommateur se reconnecte seul (déjà constaté au S2-J3). Le ticket créé
pendant la coupure n'est pas analysé — c'est la contrepartie assumée d'une publication qui ne fait
jamais échouer l'opération métier. Le rattrapage se fait par ré-import ou par un futur balayage des
tickets sans analyse ; c'est une dette, elle est écrite ici.

---

## 5. Ce que ce protocole ne mesure pas

À dire tel quel en soutenance plutôt que de laisser croire le contraire :

- **Un seul poste, tout en Docker local.** Base, backend, service IA et injecteur se disputent le
  même processeur : les chiffres sont un plancher, pas une capacité.
- **Pas de mesure du service IA sous charge.** L'analyse d'un ticket dépend d'un modèle distant
  dont le débit est celui d'un fournisseur externe. Le mesurer mesurerait Groq.
- **Pas de test d'endurance.** Deux minutes par scénario montrent un régime établi, pas une fuite
  mémoire ni une dégradation d'index sur plusieurs heures.
