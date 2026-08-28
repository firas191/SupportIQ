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
