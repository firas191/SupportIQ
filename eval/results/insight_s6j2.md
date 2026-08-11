# Suite d'evaluation text-to-SQL (S6-J2)

- **30 questions**, dont 3 attendant un refus.
- Comparaison par **resultat d'execution**, jamais par texte du SQL : deux requetes correctes s'ecrivent rarement pareil.
- Les deux questions d'exemple du prompt sont **exclues** de la suite.
- Objectif du rapport §9 : **≥ 80 %**.

## Resultat

| Mesure | Valeur |
|---|---|
| **Reussite stricte** | **26/30 (87%)** |
| Reussite souple (ordre des colonnes ignore) | 26/30 (87%) |
| Refus corrects sur questions hors perimetre | 2/3 |
| Requetes reparees par la boucle | 0 |

## Echecs


### #2 — Combien de tickets ont deja ete analyses ?

**Cause** : resultat different (1 lignes contre 1 attendues)

SQL genere :

```sql
SELECT COALESCE(SUM(ticket_count), 0) AS nb_tickets FROM v_category_trends LIMIT 500
```

SQL de reference :

```sql
SELECT analyzed_tickets FROM v_ticket_stats
```


### #6 — Combien de tickets analyses sont de priorite HIGH ?

**Cause** : resultat different (1 lignes contre 1 attendues)

SQL genere :

```sql
SELECT COUNT(*) AS nb_tickets FROM v_tickets WHERE category = 'HIGH' AND is_analysed = TRUE LIMIT 500
```

SQL de reference :

```sql
SELECT COUNT(*) AS nb FROM v_tickets WHERE priority = 'HIGH'
```


### #19 — Combien de reponses ont ete generees au total, quel que soit leur statut ?

**Cause** : resultat different (1 lignes contre 1 attendues)

SQL genere :

```sql
SELECT SUM(attempts) AS nb_responses FROM v_draft_activity LIMIT 500
```

SQL de reference :

```sql
SELECT COUNT(*) AS nb FROM v_draft_activity
```


### #30 — Quel est le salaire de chaque agent du support ?

**Cause** : a repondu au lieu de refuser

SQL genere :

```sql
SELECT * FROM v_tickets LIMIT 500
```


Lire ces cas un par un : c'est la que se decide s'il faut corriger le prompt, les vues, ou la question elle-meme (une question ambigue n'a pas de reponse de reference legitime).

## Limites

- Les questions sont **precises par construction**. Une question ambigue (« repartis les humeurs » : avec ou sans les tickets non analyses ?) admet plusieurs reponses justes, et aucun bareme ne peut trancher. La suite mesure la traduction de questions claires.
- Le SQL de reference est ecrit par la meme personne que le prompt. Un biais subsiste : je formule les questions comme je concois les vues.
- Les donnees sont celles de la base de developpement. Un resultat vide des deux cotes compte comme une reussite, alors qu'il ne demontre pas grand-chose.
