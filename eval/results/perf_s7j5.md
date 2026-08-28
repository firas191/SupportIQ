# Rapport de performance — 50 000 tickets (S7-J5)

> **Statut : à remplir.** Ce document est le canevas du rapport, avec ses tableaux vides et les
> questions auxquelles chaque mesure doit répondre. Le protocole complet est dans `perf/README.md`.
>
> Il est commité vide **à dessein** : les chiffres seront ceux d'une exécution réelle, sur une
> machine nommée, et non des ordres de grandeur écrits d'avance. Un rapport de performance rédigé
> sans avoir tourné est le plus facile à produire et le seul qui ne vaut rien.

## Contexte de mesure

| | |
|---|---|
| Date | *à remplir* |
| Machine | *processeur, cœurs, mémoire, type de disque* |
| Déploiement | Docker Compose local — base, backend, service IA et injecteur sur le même hôte |
| Volume | 50 000 tickets, *N* analysés, *N* scorés SLA |
| Version | commit *à remplir* |

> **Tout est sur un seul poste.** Base, application et injecteur se disputent le même processeur :
> les chiffres ci-dessous sont un **plancher**, pas une capacité de production.

## 1. Plans d'exécution — avant / après `V18`

| Requête | Plan avant | Plan après | Temps avant | Temps après |
|---|---|---|---|---|
| File filtrée par statut | | | | |
| Recherche plein texte | `Bitmap Index Scan on ix_tickets_search_vector` (attendu) | idem | | |
| Pagination `OFFSET 10000` | | *inchangé, attendu* | | |

**Verdict sur `ix_tickets_status_created` :** *conservé / retiré*.

Si le plan de la première ligne ne change pas, l'index doit être **retiré** : il se paie à chaque
écriture sur la table la plus écrite du projet, et n'accélère alors aucune lecture.

## 2. Tirs k6

### `search.js` — file de tickets

| Scénario | P50 | P95 | P99 | Débit (req/s) | Erreurs |
|---|---|---|---|---|---|
| `list` (sans filtre) | | | | | |
| `filtered` (statut + catégorie) | | | | | |
| `fulltext` | | | | | |
| `deepPage` (page 500) | | | | | |

Objectif §9 : **P95 < 300 ms** sur les trois premiers. `deepPage` est mesuré sans seuil — c'est la
limite connue de la pagination par offset, documentée en §4 ci-dessous.

### `dashboard.js` — vue d'ensemble et fiche

| Scénario | P50 | P95 | P99 | Erreurs |
|---|---|---|---|---|
| `kpis` / `trends` — **cache froid** | | | | |
| `kpis` / `trends` — cache chaud | | | | |
| `detail` (fiche complète) | | | | |

La distinction froid/chaud n'est pas un détail : les deux endpoints sont cachés 60 s (S4-J1), et
sans elle on publierait un P95 de quelques millisecondes en croyant avoir mesuré des agrégats sur
50 000 tickets.

## 3. Résilience de la chaîne asynchrone

| Scénario | Attendu | Observé |
|---|---|---|
| `SIGKILL` sur le worker en plein lot | messages non acquittés remis en file, aucun perdu | |
| Rejeu après redémarrage | `COUNT(analyses)` rejoint `COUNT(tickets)` | |
| Doublons d'analyse après rejeu | **aucun** — `UNIQUE (ticket_id)` (V3) | |
| Message empoisonné | part en DLQ `tickets.analyze.dlq`, ne bloque pas la file | |
| Broker arrêté, webhook appelé | l'API répond 202, le ticket est créé, l'analyse manque | |
| Broker redémarré | le consommateur se reconnecte seul | |

**Le point qui compte** : l'acquittement n'a lieu qu'après traitement complet (`message.process()`,
S2-J3). Une mort brutale ne peut donc pas *perdre* un message — au pire elle en fait *rejouer* un.
Et le rejeu est sûr parce que `analyses` porte une contrainte d'unicité : c'est elle qui transforme
« au moins une fois » en « exactement une fois » du point de vue des données.

## 4. Limites connues, non corrigées

- **Pagination par offset.** `OFFSET 10000` fait produire puis jeter dix mille lignes ; aucun index
  n'y change rien. La correction est une pagination par curseur, donc un changement de contrat
  d'API — pas quelque chose qu'on glisse un jour de test de charge. À chiffrer : à partir de
  quelle page la latence dépasse-t-elle l'objectif ?
- **Tri par risque SLA.** Il traverse une jointure externe que l'index `ix_sla_risks_desc` ne peut
  pas piloter. Corriger demanderait de dénormaliser le score dans `tickets`, ce qui casserait la
  frontière tenue depuis la semaine 3. À reconsidérer **si** la mesure le désigne.
- **Ticket créé pendant une coupure du broker.** Il n'est jamais analysé : la publication est
  best-effort et postérieure au commit, ce qui garantit que l'opération métier ne dépend pas du
  broker — au prix de ce trou. Le rattrapage (balayage des tickets sans analyse) est une dette
  écrite, pas un oubli.
- **Service IA hors périmètre.** Son débit est celui d'un fournisseur de modèles distant : le
  mesurer mesurerait Groq.

## 5. Conclusion

*À rédiger après mesure. Trois phrases suffisent : l'objectif est-il tenu, à quel volume, et quelle
est la première chose qui cédera si le volume double.*
