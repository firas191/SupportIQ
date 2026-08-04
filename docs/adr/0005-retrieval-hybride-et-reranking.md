# ADR-0005 — Retrieval hybride et reranking de la base de connaissances

- **Statut** : accepté
- **Date** : S5-J2
- **Contexte** : rapport §9 Semaine 5 — « Retrieval hybride : BM25 + vecteurs, fusion RRF,
  reranking cross-encoder ; éval recall@5 sur 40 paires question/chunk annotées »

## Contexte

La base de connaissances livrée au S5-J1 utilisait une recherche purement vectorielle
(multilingual-e5-base + pgvector). Le planning prévoit deux étages supplémentaires : une recherche
**lexicale BM25** fusionnée par *Reciprocal Rank Fusion*, puis un **reranking par cross-encodeur**.

Attente théorique : BM25 rattrape les requêtes à **termes rares** que le vecteur dilue ; le
cross-encodeur corrige la faible **discrimination** du bi-encodeur constatée au J1 (les cinq premiers
résultats tenaient dans 6 points de cosinus).

## Mesure

44 paires question/fragment annotées à la main, couvrant les 20 sections du corpus de démonstration
(8 questions en anglais). Annotation par `(source, heading)` — les identifiants de fragment changent
à chaque ré-import.

| Régime | recall@1 | recall@3 | recall@5 | MRR | latence |
|---|---|---|---|---|---|
| Vectoriel seul | 0,886 | 0,955 | **0,955** | **0,913** | 58,0 ms |
| BM25 seul | 0,841 | 0,932 | 0,932 | 0,883 | 0,1 ms |
| RRF (vecteurs + BM25) | 0,864 | 0,932 | **0,955** | 0,900 | 58,6 ms |
| RRF + reranking | 0,818 | 0,886 | 0,932 | 0,859 | 1 018,6 ms |

### Correction d'une mesure erronée

Une première exécution donnait **17 208 ms** pour le reranking, chiffre annoncé et faux : il
incluait le **téléchargement** du modèle (471 Mo à ~820 kB/s, soit ~9,6 minutes) amorti sur les
44 questions. Le harness effectue désormais une **passe à blanc** avant de chronométrer. Le surcoût
réel du reranking est de **~17×** (1 019 ms contre 58 ms), non de 170×.

Le même défaut faisait apparaître le vectoriel seul (103 ms) plus lent que la fusion qui l'englobe
(73 ms) — un résultat impossible, qui aurait dû alerter immédiatement.

## Analyse des désaccords

L'agrégat masque l'essentiel : 0,013 de MRR sur 44 questions, c'est **une** question. Le harness
produit donc une table des 14 questions où les régimes divergent, avec le rang du bon fragment.

### Vectoriel contre RRF : **3 victoires partout**

RRF rattrape ce que le vecteur manque totalement :

| Question | Vectoriel | RRF |
|---|---|---|
| « le site plante quand je valide ma commande, ai-je été débité » | **absent** | 4 |
| « j'ai reçu un produit cassé » | 3 | **1** |
| « le suivi n'a pas bougé depuis dix jours » | 3 | **2** |

Mais RRF perd ce que le vecteur classait en tête :

| Question | Vectoriel | RRF |
|---|---|---|
| « je veux être remboursé de ma commande, quelle est la procédure » | **1** | absent |
| « mes articles disparaissent entre deux visites » | **1** | 2 |
| « j'ai besoin du justificatif comptable de mon achat » | **2** | 3 |

**Le mécanisme de la régression.** Avec `pool_factor = 4` et `k = 5`, chaque moteur remonte
20 candidats — soit **la totalité** du corpus. BM25 verse donc dans la fusion tous les fragments
ayant un terme en commun, même faiblement. Ces candidats médiocres reçoivent du crédit de rang, et
un document moyen chez les deux moteurs finit par dépasser un document excellent chez un seul. Sur
un corpus réaliste, cette queue n'entrerait jamais dans la fusion : c'est un **artefact de taille**,
pas un défaut de RRF.

### Reranking contre RRF : **5 victoires contre 7**

Et surtout, les défaites du reranking sont **catastrophiques** : trois questions passent du rang 1 à
*absent du top 5* (« j'ai reçu un produit cassé », « do I have to pay for the return shipping »,
« mes articles disparaissent »). Ce n'est pas une dégradation graduelle, c'est un comportement
**erratique** — signature d'un décalage de domaine entre un reranker entraîné sur des passages de
recherche web (mMARCO) et de la prose de FAQ structurée en procédures.

## Décisions

1. **Reranking désactivé** (`rerank_enabled = False`). 17× le coût pour un MRR qui recule de 0,900 à
   0,859, avec des défaillances brutales sur des questions que les autres régimes traitent
   parfaitement. Le code reste en place et réactivable : la conclusion vaut pour **ce modèle, ce
   corpus, cette inférence CPU**, pas pour le principe du reranking.

2. **Mode par défaut : hybride (RRF) sans reranking.** Et il faut être clair sur la nature de ce
   choix : **ce corpus ne permet pas de départager** vectoriel et RRF. L'agrégat les sépare d'une
   question, les duels sont à égalité parfaite. La décision repose donc sur trois arguments
   extérieurs à la mesure :
   - le coût est **nul** (0,6 ms) ;
   - RRF rattrape un échec **total** du vecteur, alors que ses propres pertes restent des reculs de
     rang — un fragment absent est plus grave qu'un fragment en 3ᵉ position ;
   - le mode de défaillance observé s'explique par la taille du corpus et disparaît à l'échelle.

   C'est un **jugement d'ingénieur documenté**, pas une conclusion de mesure. Il doit être réévalué
   dès que le corpus dépasse quelques centaines de fragments.

## Conséquences

- La latence de recherche reste au niveau du J1 (~58 ms).
- Le J3 (agent Résolution) est libéré d'une seconde d'attente avant même le premier appel au modèle
  de génération.
- `retrieval_pool_factor` devient le paramètre à surveiller : tant que `k × facteur` approche la
  taille du corpus, la fusion travaille sur des données dégradées.

## Ce que cela illustre

Une brique prévue au planning a été implémentée, mesurée, puis **désactivée sur la base de la
mesure**. Et un chiffre annoncé a dû être corrigé d'un facteur dix parce que le protocole était
fautif. Les deux se défendent en soutenance : un harness qui ne peut jamais infirmer ne sert à rien,
et une mesure qu'on ne remet pas en question n'est pas une mesure.
