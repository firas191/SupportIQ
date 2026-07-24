# Évaluation du pipeline hybride — test set gelé (S3-J5)

- Test : **300 tickets** gelés (`eval/datasets/test.jsonl`).
- Métrique : **macro-F1** par tête. Priorité exclue (dérivée par règles, ADR-0003).

## Régimes (macro-F1)

| Tête | Local seul (ONNX) | LLM seul (0-shot) |
|---|---|---|
| category | 0.95 | 0.87 |
| sentiment | 0.60 | 0.70 |

## Balayage du seuil de confiance (hybride)

| Seuil | Taux d'escalade | F1 catégorie | F1 sentiment |
|---|---|---|---|
| 0.50 | 46% | 0.95 | 0.69 |
| 0.60 | 77% | 0.95 | 0.70 |
| 0.70 | 91% | 0.95 | 0.70 |
| 0.80 | 98% | 0.94 | 0.72 |
| 0.90 | 100% | 0.92 | 0.72 |

**Lecture (ADR-0004)** : chaque escalade coûte un appel LLM (latence + tokens). On cherche le seuil le plus **bas** qui garde un F1 proche du meilleur — c'est le meilleur compromis coût/qualité. Voir `docs/adr/0004-seuil-routeur-confiance.md`.