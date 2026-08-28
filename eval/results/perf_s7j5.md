# Rapport de performance — 63 057 tickets (S7-J5)

> **Statut : section 1 mesurée. Sections 2 et 3 à remplir** (tirs k6, scénario de résilience).
> Le protocole complet est dans `perf/README.md`.
>
> Ce document a été commité **vide** et se remplit au fur et à mesure des exécutions réelles. Un
> rapport de performance rédigé sans avoir tourné est le plus facile à produire et le seul qui ne
> vaut rien.

## Contexte de mesure

| | |
|---|---|
| Date | 28 août 2026 |
| Machine | *à compléter — processeur, cœurs, mémoire, type de disque* |
| Déploiement | Docker Compose local — base, backend, service IA et injecteur sur le même hôte |
| Volume | **63 057 tickets** (50 000 du corpus `PERF-`, le reste hérité des semaines précédentes), 3 041 analysés, 10 033 scorés SLA |
| Statuts | RESOLVED 37 500 (59,5 %) · NEW 20 557 (32,6 %) · IN_PROGRESS 5 000 (7,9 %) |
| Version | commit *à remplir* |

> **Tout est sur un seul poste.** Base, application et injecteur se disputent le même processeur :
> les chiffres ci-dessous sont un **plancher**, pas une capacité de production.

### Deux précautions sans lesquelles la mesure ne voulait rien dire

**1. La répartition des statuts.** La première exécution a donné deux plans identiques avec et sans
l'index. Ce n'était pas un résultat : les 63 057 tickets étaient **tous** au statut `NEW`, puisque
rien n'est jamais résolu dans ce projet. Un filtre qui sélectionne 100 % d'une table n'a évidemment
pas besoin d'index. `perf/seed_status.sql` rend la répartition plausible avant de remesurer.

**2. Le `VACUUM`.** L'`UPDATE` de 50 000 lignes crée 50 000 nouvelles versions sans libérer les
anciennes : la table double de taille et le planificateur change d'avis pour de mauvaises raisons.
Constaté en direct — la recherche plein texte est passée d'un `Bitmap Index Scan` à 7,5 ms à un
`Seq Scan` à 17,3 ms, puis est revenue à 7,6 ms après `VACUUM`. **Mesurer juste après une
modification en masse, c'est mesurer le désordre laissé par la préparation des données.**

## 1. Plans d'exécution — l'index `V18`

| Requête | Plan | Temps | Pages lues |
|---|---|---|---|
| File `status='NEW'`, **avec** l'index | `Seq Scan` + 2 hash joins + tri sur 20 557 lignes | 45,7 ms | 8 532 |
| La même, **sans** l'index | identique | 32,6 ms | 8 526 |
| La même, **triée et limitée avant les jointures** | `Index Only Scan`, `Heap Fetches: 0`, 3 nested loops sur 20 lignes | **3,7 ms** | **251** |
| `COUNT(*)` de pagination | `Index Only Scan using ix_tickets_status_created`, jointures éliminées | 2,5 ms | 105 |
| Recherche plein texte (`remboursement`, 7 866 résultats) | `Bitmap Index Scan on ix_tickets_search_vector` | 7,6 ms | 3 474 |
| Pagination `OFFSET 10000` | `Parallel Seq Scan` + `Gather Merge` | 21,1 ms | 8 423 |

**Verdict sur `ix_tickets_status_created` : conservé, et la requête réécrite.**

L'index n'était choisi par aucun plan. La règle écrite d'avance — *« si le plan ne change pas,
retirer l'index »* — conduisait donc à le supprimer, et c'est bien la conclusion que j'avais
annoncée avant la troisième mesure.

Elle était fausse, pour une raison qui tient à la requête et non à l'index : le `ORDER BY`
s'applique **après** les deux `LEFT JOIN`, donc PostgreSQL joignait les 20 557 lignes filtrées pour
en garder vingt. J'avais écrit dans l'en-tête de `V18` que « `LIMIT 20` s'arrête après vingt
lignes » — vrai d'une requête sur la seule table `tickets`, faux de celle-ci.

En sélectionnant d'abord les identifiants de la page (filtre, tri et limite sur `tickets` seule),
puis en ne joignant que ces vingt lignes, l'index devient un `Index Only Scan` qui ne touche jamais
la table : **12 fois plus rapide, 34 fois moins de pages**. `TicketSearchRepository` a été réécrit
en conséquence, en n'incluant dans la sous-requête que les jointures que le filtre ou le tri
exigent — les ajouter systématiquement annulerait le gain.

Le `COUNT` de pagination, lui, utilisait déjà l'index sans rien changer : 105 pages au lieu des
8 335 d'un parcours complet.

**Ce que cet épisode corrige dans la méthode** : « si le plan ne change pas, retirer l'index »
suppose que la requête soit *capable* de s'en servir. Avant de déclarer un index mort, il faut avoir
essayé au moins une reformulation. Sinon on ne mesure pas l'index, on mesure la forme qu'on lui a
imposée.

## 1 bis. Un défaut trouvé par accident

La requête de vérité terrain ajoutée à `seed_status.sql` renvoie **zéro dépassement et zéro respect
d'échéance**, alors que 37 500 tickets ont un `resolved_at`. Cause : `sla_due_at` est `NULL` sur
tout le corpus importé.

Au S7-J3, le calcul d'échéance a été accroché à `TicketAnalyzedListener`, parce que la priorité —
qui détermine le budget — n'est connue qu'après analyse. La conséquence n'avait pas été anticipée :
**un ticket jamais analysé n'a jamais d'échéance**, donc il ne peut ni apparaître à risque, ni
compter comme dépassé. Si la file d'analyse prend du retard, ces tickets sortent silencieusement du
dispositif SLA — exactement le mode de défaillance qu'un indicateur de SLA doit rendre visible.

La correction naturelle serait de poser une échéance par défaut (budget `MEDIUM`) **à la création**,
puis de l'affiner à l'analyse. Elle n'est pas faite ici : c'est un changement de comportement métier
qui mérite sa décision, pas un correctif de journée de performance.

## 2. Tirs k6

### `search.js` — file de tickets

10 utilisateurs simultanés, montée sur 30 s, deux minutes en régime établi, descente sur 30 s.

**Premier tir — un seuil dépassé.**

| Scénario | P50 | P95 | Max | Verdict |
|---|---|---|---|---|
| `list` | 34 ms | 52 ms | 133 ms | ✓ |
| `filtered` | 20 ms | 32 ms | 78 ms | ✓ |
| **`fulltext`** | **288 ms** | **573 ms** | **812 ms** | **✗** |
| `deepPage` | 42 ms | 62 ms | 120 ms | *(sans seuil)* |

Débit : 80,6 req/s · 0 erreur.

**Cause — un défaut qui avait survécu à sa propre vérification.** La recherche plaçait le choix de
la configuration linguistique **à l'intérieur** de `websearch_to_tsquery` :

```sql
t.search_vector @@ websearch_to_tsquery(
    CASE WHEN t.language = 'en' THEN 'english' ELSE 'french' END, ?)
```

Le côté droit du `@@` dépend alors de la ligne en cours. Un index se parcourt avec une clé ; sans
clé constante, PostgreSQL lisait les 63 057 lignes en appelant la fonction à chaque ligne, et le
`ts_rank` du tri faisait de même.

Le défaut datait du S4-J3, où la recherche avait pourtant été « vérifiée » par un `EXPLAIN ANALYZE`
montrant un `Bitmap Index Scan` à 0,217 ms. Ce plan était réel — mais il portait sur une requête
écrite à la main avec `'french'` en dur, **que l'application n'exécute pas**. Même erreur que sur
l'index V18, le même jour : mesurer une forme et conclure sur une autre.

**Pourquoi seule la charge l'a révélé.** Seul, le plan séquentiel se parallélise sur deux processus
auxiliaires et tient 79 ms — parfaitement acceptable. À dix utilisateurs, chacun en réclamant deux,
la machine se dispute ses propres cœurs. *Un plan parallèle masque son coût tant qu'on mesure
seul.*

**Correction :** deux branches à configuration constante réunies par `OR`, et le `CASE` sorti de
l'appel de fonction dans le `ts_rank`. Équivalence contrôlée avant de toucher au code — 7 866 = 7 866
en français, 7 756 = 7 756 en anglais.

**Second tir — tous les seuils tenus.** *(via `host.docker.internal`)*

| Scénario | P50 | P95 | Max | Verdict |
|---|---|---|---|---|
| `list` | 29 ms | 40 ms | 92 ms | ✓ |
| `filtered` | 18 ms | 25 ms | 61 ms | ✓ |
| `fulltext` | 52 ms | 127 ms | 326 ms | ✓ |
| `deepPage` | 37 ms | 49 ms | 105 ms | *(sans seuil)* |

Débit : 188,6 req/s · **8 échecs sur 36 605 (0,02 %)**.

**Troisième tir — mesure de référence, dans le réseau de compose.**

| Scénario | P50 | **P95** | Max | Verdict |
|---|---|---|---|---|
| `list` | 24 ms | **33 ms** | 78 ms | ✓ |
| `filtered` | 14 ms | **19 ms** | 45 ms | ✓ |
| `fulltext` | 47 ms | **117 ms** | 185 ms | ✓ |
| `deepPage` | 32 ms | **42 ms** | 83 ms | *(sans seuil)* |

Débit : **258,7 req/s** · **0 échec sur 46 632 contrôles**.

Objectif §9 — **P95 < 300 ms** — tenu sur les trois endpoints critiques, avec une marge d'un facteur
2,5 sur le plus lent.

### Deux enseignements de ces trois tirs

**1. Une requête mal planifiée ne coûte pas qu'à celle qui l'exécute.** Entre le premier et le
deuxième tir, personne n'a demandé plus de travail : seule la recherche plein texte a changé. Le
débit global a pourtant été multiplié par 2,3 et les trois *autres* profils ont accéléré. La
recherche réclamait deux processus auxiliaires par requête et privait les autres de cœurs.

**2. Le chemin de mesure fait partie de la mesure.** Les 8 échecs du deuxième tir étaient des
`dial: i/o timeout` — la connexion TCP ne s'ouvrait pas — groupés dans les quinze dernières
secondes, alors qu'aucune requête réellement partie n'avait dépassé 326 ms. Diagnostic : le
mandataire réseau de Docker Desktop, à court de connexions après ~36 000 ouvertures.

Vérifié plutôt qu'affirmé, en rejouant le tir dans le réseau de compose : **zéro échec**, et le
débit passe de 188 à 259 req/s. Le mandataire ne provoquait donc pas seulement les coupures de fin
de tir, il prélevait aussi ~25 % de la latence sur chaque requête. Sans ce contrôle, le rapport
aurait publié des chiffres 25 % trop pessimistes **et** un taux d'erreur imputé à tort à
l'application.

### `dashboard.js` — vue d'ensemble et fiche

8 utilisateurs simultanés, 2 min 10 s, dans le réseau de compose.

| Scénario | P50 | P95 | Max | Verdict |
|---|---|---|---|---|
| `kpis` / `trends` — cache chaud | < 1 ms | < 1,1 ms | 107 ms | ✓ |
| `detail` (fiche complète) | 1,3 ms | **2,0 ms** | **4,0 s** | ✓ |

595 449 contrôles · **0 échec** · 4 572 req/s.

**Cache froid, mesuré à part — 108 ms contre 14 ms.** Le débit ci-dessus (vingt fois celui de la
recherche) dit à lui seul ce qui est mesuré : des accès au cache, pas des agrégats. Les trois vues
sur 63 057 tickets coûtent **~95 ms** une fois par minute et par instance ; Caffeine ramène tout le
reste sous la milliseconde.

**Ce chiffre situe la porte de sortie du S4-J1.** À 10 022 tickets, le premier appel coûtait 18 ms
(mesuré ce jour-là) ; à 63 057, il en coûte ~95. Six fois plus de données pour cinq fois plus de
temps — croissance à peu près linéaire, donc le seuil de 300 ms serait atteint vers **400 à
500 000 tickets**. C'est là qu'il faudra passer en vues matérialisées, pas avant.

### Deux réserves sur cette mesure, à ne pas passer sous silence

**1. `dashboard_cold_duration` du script est inexploitable.** Il isole la première itération de
chaque utilisateur virtuel (`__ITER === 0`), mais seul le tout premier tombe sur un cache vide : les
sept autres démarrent quelques secondes plus tard et lisent déjà le cache. D'où un `avg = 15,6 ms`
pour une `med = 849 µs` — un seau majoritairement rempli de valeurs chaudes. C'est un défaut de
conception du scénario, et c'est pour cela que la mesure froide a été refaite à part, en attendant
l'expiration du TTL.

**2. k6 rapporte des durées négatives** (`min = -1 393 786 ns`) sur les endpoints cachés. Une
latence négative n'existe pas : la résolution de l'horloge est atteinte. Les valeurs « chaudes » sont
donc reportées comme « sous la milliseconde » et non comme des chiffres. *Quand un instrument rend
une valeur impossible, ses lectures à cette échelle ne se citent pas.*

**Un pic isolé à 4 s sur `detail`**, contre un P95 à 2 ms. C'est le seul endpoint mesuré qui fasse un
**appel HTTP sortant synchrone** — vers FastAPI, pour les tickets similaires — et il porte donc une
queue de distribution que les autres n'ont pas. Une occurrence unique sur 198 483 ne permet pas de
l'attribuer (recherche vectorielle réelle, réveil du service, pause du ramasse-miettes) ; elle est
notée, pas expliquée. Le délai de lecture du client est à 15 s : un service IA lent ne peut donc pas
bloquer un fil Tomcat indéfiniment, mais il peut faire attendre l'agent plusieurs secondes.

## 2 bis. Une perte de messages, trouvée en préparant le test de résilience

Avant de tuer le worker, contrôle de l'état de départ : les trois files à **0**, et 63 057 tickets
pour **3 041 analyses**. Or l'import des 50 000 tickets `PERF-` avait été fait avec le consommateur
arrêté — ces 50 000 événements auraient dû l'attendre dans `tickets.analyze`.

**Cause.** Les files sont déclarées `durable` et Spring publie en mode persistant : un message
survit donc à un *redémarrage* du courtier. Il ne survit pas à la *recréation* du conteneur, car la
base mnesia de RabbitMQ vit dans sa couche inscriptible — et `rabbitmq` était le seul service avec
état du `docker-compose.yml` à **n'avoir aucun volume**, là où `postgres` en avait un depuis le
premier jour.

**Pourquoi c'est plus qu'un oubli de configuration.** Toute la démonstration de résilience du S2-J3
tient dans « un message non acquitté n'est jamais perdu ». C'est vrai du processus *consommateur*,
et c'était faux dès qu'on touchait au *courtier* — sans que rien ne le signale, puisque publier dans
une file durable réussit parfaitement. Volume ajouté (`rabbitmq-data`).

**Et la dette écrite plus bas s'est réalisée par un autre chemin.** Ce rapport notait déjà : *« un
ticket créé pendant une coupure du broker n'est jamais analysé ; le rattrapage par balayage des
tickets sans analyse reste à faire »*. La cause supposée était une publication ratée ; la cause
réelle est une perte au courtier. **La conséquence est identique, le remède aussi** — et elle est
aujourd'hui mesurable : *60 016 tickets sur 63 057 sans analyse, et rien dans la plateforme ne le
dit.*

Le rattrapage cesse donc d'être une précaution théorique pour devenir un correctif à faire (S8-J1,
passe de bugs) :

```sql
SELECT COUNT(*) FROM tickets t
LEFT JOIN analyses a ON a.ticket_id = t.id
WHERE a.ticket_id IS NULL;
```

*Une garantie de durabilité qu'on n'a jamais mise en défaut volontairement n'est pas une garantie
vérifiée.* Celle-ci a tenu sept semaines sans être exercée, et c'est le jour où on préparait le test
qui l'a rompue.

## 3. Résilience de la chaîne asynchrone

**Protocole.** Import de 300 tickets (`KILL-`), `SIGKILL` sur `ai-service` en plein traitement, puis
redémarrage. Le `prefetch_count` de la file étant de **20**, le worker ne détient jamais plus de
20 messages non acquittés : le nombre de messages en jeu au moment de la mort était donc **prédit
avant la mesure**, ce qui rend le test réfutable.

| Instant | `messages` | `unacknowledged` | analyses en base |
|---|---|---|---|
| Avant le kill | 300 | **20** | — |
| Après le kill (+2 s) | 224 | 0 | **86** |
| Après redémarrage | **0** | 0 | **300** |

Doublons dans `analyses` : **0**.

### Ce que l'arithmétique révèle — et qui vaut mieux qu'un résultat propre

`86 + 224 = 310`, soit **dix de plus** que les 300 publiés. Ce n'est pas une incohérence, c'est la
mesure elle-même :

| | |
|---|---|
| Messages acquittés au moment du kill | `300 − 224 = 76` |
| Analyses réellement écrites | `86` |
| **Écart** | **10** |

Les vingt messages que le worker tenait en main se répartissaient en **dix pas encore traités** et
**dix traités mais non acquittés** — morts entre l'écriture en base et l'acquittement. RabbitMQ, ne
voyant pas d'acquittement, a remis les vingt en file, et les dix déjà analysés sont repassés une
seconde fois dans le pipeline.

**C'est la sémantique « au moins une fois », observée plutôt qu'affirmée.** Aucune perte n'est
possible parce que l'acquittement suit le traitement (`message.process()`, S2-J3) ; en revanche le
rejeu est inévitable, et il l'est pour une raison de fond : il n'existe aucune transaction commune
entre PostgreSQL et RabbitMQ. Entre « écrire l'analyse » et « acquitter le message », il y a
toujours un instant où l'un est fait et l'autre non.

**La garantie « exactement une fois » n'est donc pas dans le courtier, elle est dans le schéma.**
C'est `UNIQUE (ticket_id)` sur `analyses` (migration V3) qui absorbe le second passage — et c'est ce
qui explique le `0` de la colonne doublons. Une contrainte d'unicité écrite en semaine 3 pour éviter
d'analyser deux fois le même ticket se révèle, en semaine 7, porter une propriété d'architecture
distribuée.

Sans elle, ce test aurait rendu `310` analyses pour 300 tickets, et personne ne l'aurait remarqué
avant qu'un tableau de bord ne compte de travers.

### Le reste du tableau

| Scénario | Attendu | Observé |
|---|---|---|
| `SIGKILL` sur le worker en plein lot | messages non acquittés remis en file, aucun perdu | **conforme** — 20 requeués |
| Rejeu après redémarrage | `analyses` rejoint `tickets` | **300 / 300** |
| Doublons après rejeu | aucun — `UNIQUE (ticket_id)` (V3) | **0** |
| Courtier recréé, consommateur vivant | reconnexion automatique, topologie redéclarée | **conforme** — repli 5 s, files et consommateurs rétablis seuls |
| Message empoisonné | part en DLQ `tickets.analyze.dlq` | *non exercé* |
| Broker arrêté, webhook appelé | 202, ticket créé, analyse manquante | *non exercé — mais voir §2 bis, le cas s'est produit à l'échelle de 50 000 tickets* |

## 4. Limites connues, non corrigées

- **Pagination par offset — beaucoup moins grave que prévu, et pour une raison instructive.**
  `OFFSET 10000` fait toujours produire puis jeter dix mille lignes, mais depuis la jointure
  différée ce sont dix mille **identifiants** issus d'un `Index Only Scan`, et non dix mille lignes
  jointes. Mesuré : page 500 à **42 ms au P95**, contre 33 ms pour la première page. La pagination
  par curseur (`WHERE (created_at, id) < (?, ?)`) reste la vraie réponse — mais elle demande un
  changement de contrat d'API, et **plus rien ne la rend urgente**. Une limite qu'on croyait
  structurelle était en partie un effet de la forme de la requête.
- **Tri par risque SLA.** Il traverse une jointure externe que l'index `ix_sla_risks_desc` ne peut
  pas piloter. Corriger demanderait de dénormaliser le score dans `tickets`, ce qui casserait la
  frontière tenue depuis la semaine 3. À reconsidérer **si** la mesure le désigne.
- **Ticket sans analyse — dette réalisée, plus une hypothèse.** Elle était écrite ici comme un
  risque théorique (publication best-effort après commit) ; elle s'est produite par un autre chemin
  (perte des messages à la recréation du courtier, §2 bis), avec **60 016 tickets sur 63 057
  concernés**. Le trou est le même dans les deux cas : *rien dans la plateforme ne signale qu'un
  ticket n'a jamais été analysé*. Le balayage de rattrapage passe de dette écrite à correctif à
  faire, S8-J1.
- **Service IA hors périmètre.** Son débit est celui d'un fournisseur de modèles distant : le
  mesurer mesurerait Groq.

## 5. Conclusion

**L'objectif est tenu.** Sur 63 057 tickets, le P95 des trois endpoints critiques est de 33 ms
(liste), 19 ms (liste filtrée) et 117 ms (recherche plein texte), contre un objectif de 300 ms —
avec un facteur 2,5 de marge sur le plus lent. La chaîne asynchrone survit à la mort brutale de son
worker sans perdre un message et sans en dupliquer un seul.

**Il ne l'était pas au premier tir**, et c'est le vrai résultat de la journée : deux défauts de plan
d'exécution attendaient dans le code, tous deux ayant survécu à une vérification antérieure parce
que cette vérification portait sur une requête écrite à la main, différente de celle que
l'application exécute. Un index jamais choisi (V18), une recherche plein texte incapable d'utiliser
son index GIN (S4-J3). **Le point commun n'est pas technique, il est méthodologique : on avait
mesuré la bonne base avec la mauvaise requête.**

**La première chose qui cédera si le volume double** n'est aucune des requêtes mesurées — elles ont
toutes de la marge. C'est le **cache du tableau de bord** : les vues d'agrégation coûtent ~95 ms à
63 000 tickets contre 18 ms à 10 000, croissance à peu près linéaire, donc le premier appel de
chaque minute franchira les 300 ms vers **400 à 500 000 tickets**. La porte de sortie est écrite
depuis le S4-J1 (vues matérialisées) et n'a pas besoin d'être ouverte aujourd'hui.

**Le défaut le plus grave trouvé ce jour-là n'est pourtant pas une question de performance.** C'est
qu'un service avec état tournait sans volume depuis sept semaines, et que la garantie de durabilité
qu'on lui prêtait n'avait jamais été mise en défaut volontairement. Elle l'a été par accident, à
50 000 messages.
