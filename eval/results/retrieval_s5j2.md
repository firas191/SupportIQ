# Retrieval de la base de connaissances — évaluation (S5-J2)

Corpus : **20 fragments** issus de 4 FAQ de démonstration.
Jeu d'évaluation : **44 paires question/fragment** annotées à la main, couvrant les 20 sections du corpus (français et anglais).

## Résultats

| Régime | recall@1 | recall@3 | recall@5 | MRR | latence |
|---|---|---|---|---|---|
| Vectoriel seul | 0.886 | 0.955 | 0.955 | 0.913 | 58.0 ms |
| BM25 seul | 0.841 | 0.932 | 0.932 | 0.883 | 0.1 ms |
| RRF (vect. + BM25) | 0.864 | 0.932 | 0.955 | 0.900 | 58.6 ms |
| RRF + reranking | 0.818 | 0.886 | 0.932 | 0.859 | 1018.6 ms |

## Desaccords entre regimes (14 questions sur 44)

Rang du bon fragment. `-` = absent des 5 premiers.

| Question | Vectoriel seul | BM25 seul | RRF (vect. + BM25) | RRF + reranking |
|---|---|---|---|---|
| je veux etre rembourse de ma commande, quelle est la procedu | 1 | - | - | 1 |
| combien de temps ai-je pour me retracter apres reception ? | 1 | 1 | 1 | 3 |
| ma carte a ete refusee au moment de valider le panier | 1 | 2 | 1 | 1 |
| pourquoi mon paiement n'aboutit pas ? | 1 | 2 | 1 | 1 |
| j'ai besoin du justificatif comptable de mon achat | 2 | 3 | 3 | 1 |
| droit a l'effacement de mes donnees personnelles | 1 | 1 | 1 | 2 |
| je veux etre livre des demain, est-ce possible | - | - | - | 5 |
| le suivi n'a pas bouge depuis dix jours | 3 | 2 | 2 | 1 |
| j'ai recu un produit casse | 3 | 1 | 1 | - |
| le carton etait ecrase a la livraison, quelles reserves emet | 1 | 1 | 1 | 2 |
| le site plante quand je valide ma commande, ai-je ete debite | - | 1 | 4 | 1 |
| mes articles disparaissent entre deux visites | 1 | - | 2 | - |
| when will I get my money back after cancelling | 1 | 1 | 1 | 4 |
| do I have to pay for the return shipping | 1 | 1 | 1 | - |

## Lecture

- **recall@5** : 0.955 → 0.932 (-0.023) entre le vectoriel seul et la chaîne complète.
- **MRR** : 0.913 → 0.859 (-0.054).

Le MRR est l'indicateur à regarder en priorité. Sur un corpus de cette taille le recall@5
sature vite — avec 20 sections, cinq candidats couvrent un quart du corpus. Le MRR, lui,
mesure si le **bon** fragment arrive en tête, ce qui est exactement ce qui compte quand
l'agent Résolution n'en citera qu'un ou deux (S5-J3).

## Ce que ces chiffres ne disent pas

- Le corpus est **petit** (20 fragments). BM25 comme les vecteurs y sont avantagés : il y a
  peu de distracteurs. Sur une base de plusieurs milliers de fragments, l'écart entre les
  régimes se creuserait, en faveur de l'hybride.
- Les questions sont **écrites par la même personne que le corpus**. Malgré l'effort de
  reformulation (vocabulaire différent, questions indirectes), elles restent plus proches
  des documents que de vraies questions clients. C'est la même réserve que sur le jeu de
  tickets synthétiques du S2-J5.
- L'annotation retient **un seul** fragment correct par question. Quand deux sections
  répondent partiellement, le régime qui remonte l'autre est compté en échec alors que sa
  réponse serait acceptable. Les chiffres sont donc un plancher, pas un plafond.

## Reproduire

```bash
docker compose exec ai-service python /eval/eval_retrieval.py
```

Le corpus est réindexé au démarrage du script : deux exécutions successives sont
comparables, quelle que soit la manipulation faite entre-temps dans l'écran d'administration.
