# Risque de dépassement SLA — LightGBM contre règle (S7-J3)

> ⚠ **Ces chiffres sont obtenus sur un historique SIMULÉ.** Aucun ticket du projet n'a
> jamais été résolu : la colonne `resolved_at` a été ajoutée le jour même (V17), et le
> corpus est synthétique. L'AUC ci-dessous mesure la capacité de LightGBM à retrouver les
> règles du simulateur de `ml/train_sla_risk.py`. Elle ne dit **rien** de sa performance
> sur des tickets réels, et ne doit pas être citée comme telle.

Échantillon : 15000 en apprentissage, 5000 en test. Taux de dépassement observé : 16.1%.

## Pouvoir discriminant

| Modèle | AUC | Brier |
|---|---|---|
| Règle — part du budget consommée | 0.896 | 0.0783 |
| LightGBM (score brut) | 0.970 | 0.0487 |
| LightGBM + calibration isotone | 0.970 | 0.0504 |

**Écart d'AUC modèle − règle : +0.074.**

La calibration ne change pas l'AUC — elle est monotone, donc elle préserve l'ordre. Elle
n'agit que sur le Brier, c'est-à-dire sur la véracité du chiffre affiché. C'est exactement
ce qu'on lui demande : le tri de la file vient de l'ordre, la phrase « 80 % de risque »
vient de la calibration.

## Variables les plus utilisées (gain)

| Variable | Gain |
|---|---|
| `hours_remaining` | 56,743 |
| `category` | 8,341 |
| `backlog` | 7,579 |
| `age_hours` | 7,326 |
| `hour_of_day` | 6,560 |
| `day_of_week` | 3,580 |
| `priority` | 1,833 |
| `sentiment` | 1,210 |
| `source` | 231 |

## Courbe de fiabilité (après calibration)

| Probabilité annoncée | Fréquence observée | Effectif |
|---|---|---|
| 0.00 | 0.01 | 3729 |
| 0.14 | 0.21 | 195 |
| 0.25 | 0.25 | 116 |
| 0.35 | 0.36 | 87 |
| 0.45 | 0.39 | 87 |
| 0.55 | 0.45 | 95 |
| 0.66 | 0.53 | 70 |
| 0.75 | 0.59 | 73 |
| 0.85 | 0.73 | 71 |
| 0.99 | 0.93 | 477 |

Part des tickets au-dessus du seuil d'attention (70%) : **12.4%**. Ce seuil n'est pas une propriété du modèle : il fixe la
taille de la file prioritaire et devrait se régler sur la capacité de l'équipe.

## Décision

Voir `docs/adr/0010-risque-sla-modele-vs-regles.md`, dont les règles ont été **écrites
avant** ces chiffres.
