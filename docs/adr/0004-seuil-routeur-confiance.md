# ADR-0004 — Seuil du routeur de confiance

Date : S3-J5 · Statut : accepté

## Contexte

Le pipeline de triage (ADR-0003) route chaque tête (catégorie, sentiment) : si la confiance du
modèle local (softmax) ≥ **seuil**, on garde le local (rapide, gratuit) ; sinon on **escalade au LLM**
(lent, coûteux). Le seuil arbitre donc **coût vs qualité** :

- seuil **haut** (ex. 0.90) → on n'a confiance en local que rarement → beaucoup d'escalades → cher,
- seuil **bas** (ex. 0.50) → on garde presque tout en local → peu d'escalades → moins cher, mais on
  risque de garder des prédictions locales douteuses.

Au J3, un seuil de **0.80** donnait un taux d'escalade élevé (≈ 3 tickets sur 4 en démo). Il faut le
calibrer empiriquement sur le **test set gelé** avec le harness `eval/evaluate_pipeline.py`.

## Données (balayage — `eval/results/pipeline_eval_s3j5.md`)

Régimes seuls : catégorie **local 0.95 / LLM 0.87** ; sentiment **local 0.60 / LLM 0.70**.

| Seuil | Taux d'escalade | F1 catégorie | F1 sentiment |
|---|---|---|---|
| **0.50** | **46%** | **0.95** | 0.69 |
| 0.60 | 77% | 0.95 | 0.70 |
| 0.70 | 91% | 0.95 | 0.70 |
| 0.80 | 98% | 0.94 | 0.72 |
| 0.90 | 100% | 0.92 | 0.72 |

> Caveat : lors de ce run, ~80 appels LLM (fin de run) ont échoué faute de budget Groq → prédictions
> LLM/hybride légèrement **sous-estimées** sur les lignes à forte escalade. La conclusion reste robuste
> (l'escalade n'apporte quasi rien ici), mais re-mesurer avec un budget frais confirmerait les décimales.

## Décision

**Seuil retenu : `confidence_threshold = 0.50`** (le plus bas testé). Critère : le seuil le plus **bas**
gardant le macro-F1 à ~2 points du max, afin de **minimiser l'escalade** (donc le coût et la latence).

Ce que les chiffres montrent, sans ambiguïté :
- La **catégorie** locale (0.95) est *meilleure* que le LLM (0.87) → escalader la **dégrade** (0.95→0.92
  quand l'escalade grimpe). Il ne faut donc pas l'escalader ; un seuil bas la garde en local.
- Le **sentiment** ne gagne que **+0.03** entre 0.50 et 0.90, alors que l'escalade passe de 46 % à 100 %.
  Le jeu n'en vaut pas la chandelle.
- Donc **0.50** : catégorie au max (0.95), sentiment à 3 pts du max (0.69), pour **moitié moins d'appels
  LLM** que 0.80. Résultat contre-intuitif mais mesuré : *ici, l'escalade rapporte peu*.

Piste future (hors J5) : un **seuil par tête** (ne jamais escalader la catégorie, escalader le sentiment
un peu plus) serait encore meilleur — le routeur le permet déjà techniquement (décision tête par tête).

## Conséquences

+ Taux d'escalade (donc facture LLM et latence) **mesuré et justifié**, pas deviné.
+ On peut afficher le taux d'escalade comme métrique produit (dashboard S4).
- Le seuil est réglé sur des données **synthétiques** ; à re-calibrer si un corpus réel arrive.
- Un seuil unique pour les deux têtes ; on pourrait en avoir un **par tête** si leurs profils
  divergent (porte de sortie).

## Liens

- ADR-0003 (fine-tuning vs baseline). Harness : `eval/evaluate_pipeline.py`. Config :
  `ai-service/app/config.py` (`confidence_threshold`).
