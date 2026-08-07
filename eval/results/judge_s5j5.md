# LLM-as-judge sur les brouillons de réponse (S5-J5)

- Échantillon : **50 tickets**, stratifiés par catégorie, ton `formal`.
- Grille : exactitude / complétude / ton, niveaux 0-1-2 ancrés (`ai-service/app/agents/judge.py`).
- Note globale = moyenne des trois critères, **ramenée à 0 si l'exactitude est nulle** : un brouillon qui affirme un fait absent des sources est inutilisable, pas perfectible.
- Juge : modèle **distinct du rédacteur** (70b contre 8b) — un modèle qui se note se préfère.

## Vue d'ensemble

| | Nombre | Part |
|---|---|---|
| Brouillons notés | 34 | 68% |
| Abstentions (hors périmètre de la base) | 16 | 32% |
| Non aboutis (quota, panne) | 0 | 0% |

## Qualité des brouillons notés

| Critère | Moyenne (0-2) |
|---|---|
| Exactitude | 1.71 |
| Complétude | 1.03 |
| Ton | 2.00 |

**Note globale moyenne : 0.78** (médiane 0.83).

**Brouillons inutilisables** (exactitude = 0) : **1** sur 34 (3%). C'est le chiffre qui compte pour un déploiement : les autres décrivent du travail de relecture, celui-ci décrit une information fausse proposée à l'envoi.

## L'indicateur de faible confiance prédit-il la note ?

| Groupe | Effectif | Note moyenne |
|---|---|---|
| Signalés « à relire » | 18 | 0.73 |
| Non signalés | 16 | 0.83 |

Écart : **+0.10**. Un écart proche de zéro signifierait que l'auto-vérification signale au hasard — le bandeau d'avertissement serait alors de la décoration, et apprendrait aux agents à ignorer les avertissements. Critère de décision fixé **avant** la mesure : voir ADR-0006.

## Par catégorie

| Catégorie | Notés | Note moyenne | Abstentions |
|---|---|---|---|
| FACTURATION | 3 | 0.89 | 0/3 |
| COMPTE | 1 | 1.00 | 0/1 |
| TECHNIQUE | 1 | 1.00 | 1/2 |
| RECLAMATION | 1 | 0.83 | 0/1 |
| DEMANDE | 1 | 1.00 | 0/1 |
| NON_ANALYSE | 27 | 0.74 | 15/42 |

Un taux d'abstention élevé sur une catégorie ne dit rien du rédacteur : il dit que la base de connaissances ne couvre pas ce sujet. C'est une consigne de travail pour l'administrateur, pas un défaut du modèle.

## Les cinq notes les plus basses

| Ticket | Note | Exactitude | Reproche du juge |
|---|---|---|---|
| 2192 | 0.00 | 0 | Missing information |
| 548 | 0.50 | 1 | Missing delivery issue |
| 4658 | 0.50 | 1 | Does not address facture |
| 1507 | 0.67 | 1 | Partial answer |
| 2329 | 0.67 | 1 | Request clarified |

L'agrégat ne suffit jamais : c'est en lisant ces cas qu'on sait s'il faut corriger le prompt, la base de connaissances ou la recherche. Leçon du S5-J2, où 0,013 de MRR représentait **une** question.

## Limites

- Le juge est un modèle, pas un client ni un agent expérimenté. Il vérifie la cohérence entre un texte et des passages ; il ne dit pas si la réponse aurait satisfait la personne.
- Les tickets sont **synthétiques** (S2-J5) et la base de connaissances écrite pour eux : la couverture mesurée ici est plus favorable qu'elle ne le serait sur un corpus réel.
- Une seule note par brouillon. Mesurer la stabilité du juge demanderait de noter deux fois le même brouillon et de comparer — non fait, faute de budget de jetons.
