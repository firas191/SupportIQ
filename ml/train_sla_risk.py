"""Entraînement du modèle de risque SLA — LightGBM + calibration isotone (S7-J3, rapport §9).

    python ml/train_sla_risk.py            # simule, entraine, evalue, ecrit les artefacts
    python ml/train_sla_risk.py --no-write # mesure seulement, n'ecrit aucun artefact

================================================================================
AVERTISSEMENT — CE QUE CETTE AUC MESURE, ET CE QU'ELLE NE MESURE PAS
================================================================================

**Il n'existe aucune vérité terrain dans ce projet.** Le label recherché est « ce ticket a-t-il
dépassé son SLA ? ». Le calculer demande `resolved_at`, une colonne ajoutée aujourd'hui même
(V17) : aucun ticket de la base n'a jamais été résolu, et le corpus est synthétique.

Deux options se présentaient. Ne rien livrer, ou construire l'historique manquant en le disant.
C'est la seconde, avec la même honnêteté qu'au S2-J5 pour le jeu de données de triage.

L'historique est donc **simulé** par `simulate()` ci-dessous, dont les règles sont écrites en clair.
Conséquence à énoncer telle quelle, en soutenance comme dans le rapport :

    L'AUC obtenue mesure la capacité de LightGBM à retrouver les règles de MON simulateur.
    Elle ne dit **rien** de sa performance sur des tickets réels.

Ce qui a de la valeur ici, et qui n'est pas conditionné à la qualité de la simulation :

  1. **La chaîne complète est construite et vérifiable** — variables partagées entre entraînement
     et service, calibration, export sans pickle, repli, persistance, affichage.
  2. **La comparaison à la baseline de règles est faite sur les mêmes données.** Si LightGBM ne bat
     pas « part du budget consommée » sur un historique que j'ai moi-même fabriqué avec des
     variables qu'il observe, il ne la battra jamais en production. C'est un test **nécessaire**,
     et il est informatif dans le sens du refus.
  3. **La décision est pré-enregistrée** (ADR-0010), avant d'avoir vu les chiffres.

Le simulateur introduit délibérément du bruit et un facteur non observé (la disponibilité des
agents). Sans eux, LightGBM retrouverait la règle exactement et l'AUC vaudrait ~1,0 — un chiffre
qui n'aurait trompé que celui qui l'affiche.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ai-service"))

# Import du module de production : c'est **la** precaution contre le decalage entrainement/service.
# Recopier la liste des variables ici aurait suffi a ouvrir la porte a un decalage silencieux.
from app.sla import features  # noqa: E402

ARTIFACTS = ROOT / "ml" / "artifacts"
REPORT = ROOT / "eval" / "results" / "sla_risk_s7j3.md"

SEED = 42
N_TICKETS = 20_000
AT_RISK_THRESHOLD = 0.70


# ---------------------------------------------------------------------------
# Simulateur — toutes les règles sont ici, en clair
# ---------------------------------------------------------------------------


def simulate(n: int, seed: int = SEED) -> tuple[list[list[float]], list[int]]:
    """Fabrique un historique de tickets résolus ou dépassés.

    Le modèle générateur, énoncé sans détour :

      - le délai de traitement suit une loi log-normale dont la médiane dépend de la **catégorie**
        (une réclamation prend plus longtemps qu'une demande d'information) ;
      - il est allongé par l'**encombrement de la file** de la catégorie ;
      - il est allongé quand le ticket arrive **hors des heures ouvrées** (le soir, le week-end,
        personne ne le prend avant le lendemain) ;
      - il est raccourci quand la priorité est **HIGH** (l'équipe les traite en premier) ;
      - il est perturbé par un facteur **non observé** — la disponibilité des agents ce jour-là —
        que le modèle ne verra jamais, et qui borne l'AUC atteignable.

    Le label est `delai_de_traitement > budget_de_la_priorite`.

    Ce que le simulateur **ne** reproduit pas, et qu'il faudrait pour prétendre à un réalisme
    quelconque : les réouvertures, les escalades, les tickets qui attendent une réponse du client,
    les congés, les astreintes. Ce n'est pas une omission, c'est la raison pour laquelle les
    chiffres ci-dessous ne sont pas une promesse.
    """
    rng = random.Random(seed)
    now = datetime.now(timezone.utc)

    median_hours = {
        "TECHNIQUE": 6.0,
        "FACTURATION": 4.0,
        "COMPTE": 3.0,
        "RECLAMATION": 12.0,
        "DEMANDE": 2.0,
    }

    vectors: list[list[float]] = []
    labels: list[int] = []

    for _ in range(n):
        priority = rng.choices(["HIGH", "MEDIUM", "LOW"], weights=[0.15, 0.6, 0.25])[0]
        category = rng.choice(list(median_hours))
        sentiment = rng.choices(["NEG", "NEU", "POS"], weights=[0.35, 0.45, 0.2])[0]
        source = rng.choices(["FILE", "WEBHOOK", "EMAIL", "MANUAL"], weights=[0.5, 0.3, 0.15, 0.05])[0]
        backlog = int(rng.expovariate(1 / 25.0))

        # Arrivee repartie sur les 60 derniers jours, avec un rythme jour/nuit realiste.
        created_at = now - timedelta(hours=rng.uniform(0, 60 * 24))
        if rng.random() < 0.7:  # 70 % des tickets arrivent en journee
            created_at = created_at.replace(hour=rng.randint(8, 18))
        else:
            created_at = created_at.replace(hour=rng.choice([*range(0, 8), *range(19, 24)]))

        budget = features.budget_hours(priority)

        # --- Delai de traitement reel -------------------------------------
        base = median_hours[category]
        base *= 1.0 + backlog / 60.0                       # file encombree
        base *= 0.55 if priority == "HIGH" else 1.0        # traitees en premier
        if created_at.hour < 8 or created_at.hour >= 19:
            base *= 1.8                                    # arrive hors heures ouvrees
        if created_at.weekday() >= 5:
            base *= 1.6                                    # week-end
        # Facteur non observe : la disponibilite de l'equipe ce jour-la. C'est lui qui empeche
        # l'AUC d'atteindre 1,0, et c'est ce qui rend la mesure interpretable.
        base *= math.exp(rng.gauss(0, 0.45))

        handling_hours = max(0.1, base * math.exp(rng.gauss(0, 0.35)))
        breached = handling_hours > budget

        # --- Instant d'observation ----------------------------------------
        #
        # Le modele est interroge sur des tickets **ouverts**, a un instant quelconque de leur vie.
        # Entrainer sur des tickets observes a leur creation seulement produirait un modele qui ne
        # verrait jamais `hours_remaining` negatif — soit precisement les tickets qui l'interessent.
        observed_at = created_at + timedelta(hours=rng.uniform(0, min(handling_hours, budget * 2)))

        vector = features.build(
            {
                "created_at": created_at,
                "sla_due_at": created_at + timedelta(hours=budget),
                "priority": priority,
                "category": category,
                "sentiment": sentiment,
                "source": source,
                "backlog": backlog,
            },
            observed_at,
        )
        vectors.append(vector)
        labels.append(1 if breached else 0)

    return vectors, labels


# ---------------------------------------------------------------------------
# Mesures — stdlib, comme le harness du S3-J5
# ---------------------------------------------------------------------------


def auc(labels: list[int], scores: list[float]) -> float:
    """AUC par la statistique de Mann-Whitney, avec gestion des ex aequo.

    Écrite à la main plutôt qu'importée de scikit-learn : la formule tient en dix lignes, et le
    conteneur d'exécution n'a pas toujours scikit-learn (même arbitrage qu'au S3-J5 pour le macro-F1).
    """
    pairs = sorted(zip(scores, labels))
    ranks: list[float] = [0.0] * len(pairs)
    i = 0
    while i < len(pairs):
        j = i
        while j + 1 < len(pairs) and pairs[j + 1][0] == pairs[i][0]:
            j += 1
        average = (i + j) / 2 + 1
        for k in range(i, j + 1):
            ranks[k] = average
        i = j + 1

    positives = sum(label for _, label in pairs)
    negatives = len(pairs) - positives
    if positives == 0 or negatives == 0:
        return float("nan")

    rank_sum = sum(rank for rank, (_, label) in zip(ranks, pairs) if label == 1)
    return (rank_sum - positives * (positives + 1) / 2) / (positives * negatives)


def brier(labels: list[int], scores: list[float]) -> float:
    """Erreur quadratique moyenne sur les probabilités — mesure la **calibration**, pas l'ordre.

    Une AUC parfaite avec un Brier médiocre décrit exactement le piège de la journée : un modèle
    qui classe bien et dont le chiffre affiché ment.
    """
    return sum((score - label) ** 2 for score, label in zip(scores, labels)) / len(labels)


def reliability(labels: list[int], scores: list[float], bins: int = 10) -> list[tuple[float, float, int]]:
    """Courbe de fiabilité : (probabilité annoncée, fréquence observée, effectif) par tranche."""
    buckets: list[list[tuple[float, int]]] = [[] for _ in range(bins)]
    for score, label in zip(scores, labels):
        buckets[min(bins - 1, int(score * bins))].append((score, label))
    return [
        (
            sum(s for s, _ in bucket) / len(bucket),
            sum(label for _, label in bucket) / len(bucket),
            len(bucket),
        )
        for bucket in buckets
        if bucket
    ]


def isotonic(labels: list[int], scores: list[float]) -> list[tuple[float, float]]:
    """Régression isotone par *pool adjacent violators*, en stdlib.

    Renvoie une table `(score_brut, probabilite)` que le service interpole. C'est cette table qui
    est exportée, et non un objet scikit-learn : un `pickle` couple l'artefact aux versions exactes
    de la bibliothèque, de numpy et de Python qui l'ont produit, et casse au chargement des mois
    plus tard, en production, sans que rien ne l'ait annoncé.
    """
    points = sorted(zip(scores, labels))
    blocks = [[x, float(y), 1] for x, y in points]  # [x, moyenne, effectif]

    merged: list[list[float]] = []
    for block in blocks:
        merged.append(block)
        while len(merged) > 1 and merged[-2][1] > merged[-1][1]:
            right = merged.pop()
            left = merged.pop()
            weight = left[2] + right[2]
            merged.append([right[0], (left[1] * left[2] + right[1] * right[2]) / weight, weight])

    # Table compacte : un point par palier suffit a l'interpolation.
    table = [(block[0], block[1]) for block in merged]
    return _thin(table, 200)


def _thin(table: list[tuple[float, float]], limit: int) -> list[tuple[float, float]]:
    if len(table) <= limit:
        return table
    step = len(table) / limit
    kept = [table[int(i * step)] for i in range(limit)]
    if kept[-1] != table[-1]:
        kept.append(table[-1])
    return kept


# ---------------------------------------------------------------------------
# Entraînement
# ---------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description="Modele de risque de depassement SLA (S7-J3)")
    parser.add_argument("--n", type=int, default=N_TICKETS)
    parser.add_argument("--no-write", action="store_true", help="mesure sans ecrire d'artefact")
    args = parser.parse_args()

    try:
        import lightgbm as lgb
    except ImportError:
        print("lightgbm requis : pip install lightgbm", file=sys.stderr)
        return 1

    print(f"Simulation de {args.n} tickets…")
    vectors, labels = simulate(args.n)

    # Decoupage **temporel-like** : simple aleatoire ici, puisque le simulateur ne porte aucune
    # derive temporelle. Sur des donnees reelles il faudrait couper par date — un modele evalue sur
    # un tirage aleatoire d'un historique reel voit le futur de ses propres exemples.
    split = int(len(vectors) * 0.75)
    # `lgb.Dataset` n'accepte pas une liste de listes — il lui faut un ndarray (ou un DataFrame).
    # `predict`, lui, tolère les listes : d'où une erreur qui n'apparaît qu'à l'entraînement.
    # La conversion est faite ici et non dans `simulate()`, pour que le simulateur reste utilisable
    # sans numpy et que `features.build` garde le même type de sortie qu'au service.
    import numpy as np

    x_train = np.asarray(vectors[:split], dtype="float64")
    x_test = np.asarray(vectors[split:], dtype="float64")
    y_train, y_test = labels[:split], labels[split:]
    print(f"  train={len(x_train)}  test={len(x_test)}  taux de depassement={sum(labels)/len(labels):.1%}")

    booster = lgb.train(
        {
            "objective": "binary",
            "metric": "auc",
            "learning_rate": 0.05,
            "num_leaves": 31,
            # Donnees tabulaires, 20 000 lignes, 9 variables : un modele plus gros memoriserait le
            # bruit du simulateur, ce qui gonflerait l'AUC de test sans rien apprendre.
            "min_data_in_leaf": 50,
            "feature_fraction": 0.9,
            "bagging_fraction": 0.8,
            "bagging_freq": 1,
            "seed": SEED,
            "verbosity": -1,
        },
        lgb.Dataset(
            x_train, label=y_train,
            feature_name=features.COLUMNS,
            categorical_feature=features.CATEGORICAL_INDICES,
        ),
        num_boost_round=300,
    )

    raw_test = list(booster.predict(x_test))
    # La calibration est apprise sur le **train**, jamais sur le test : l'apprendre sur le test
    # ferait de la mesure de calibration une mesure de son propre apprentissage.
    table = isotonic(y_train, list(booster.predict(x_train)))
    calibrated = [_apply(table, value) for value in raw_test]

    priorities = [_priority_of(vector) for vector in x_test]
    baseline = [
        max(0.0, min(1.0, features.consumed_fraction(vector, priority)))
        for vector, priority in zip(x_test, priorities)
    ]

    results = {
        "auc_model": auc(y_test, raw_test),
        "auc_baseline": auc(y_test, baseline),
        "brier_raw": brier(y_test, raw_test),
        "brier_calibrated": brier(y_test, calibrated),
        "brier_baseline": brier(y_test, baseline),
        "breach_rate": sum(y_test) / len(y_test),
        "at_risk_share": sum(1 for s in calibrated if s >= AT_RISK_THRESHOLD) / len(calibrated),
        "importance": sorted(
            zip(features.COLUMNS, booster.feature_importance("gain")),
            key=lambda pair: pair[1], reverse=True,
        ),
        "reliability": reliability(y_test, calibrated),
        "n_train": len(x_train),
        "n_test": len(x_test),
    }

    _print_summary(results)

    if not args.no_write:
        ARTIFACTS.mkdir(parents=True, exist_ok=True)
        booster.save_model(str(ARTIFACTS / "sla_risk.txt"))
        (ARTIFACTS / "sla_calibration.json").write_text(
            json.dumps([[round(x, 6), round(y, 6)] for x, y in table]), encoding="utf-8"
        )
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_text(_report(results), encoding="utf-8")
        print(f"\nArtefacts : {ARTIFACTS}\nRapport   : {REPORT}")

    return 0


def _priority_of(vector: list[float]) -> str | None:
    code = int(vector[features.COLUMNS.index("priority")])
    vocabulary = features.VOCABULARIES["priority"]
    return vocabulary[code] if 0 <= code < len(vocabulary) else None


def _apply(table: list[tuple[float, float]], raw: float) -> float:
    import bisect

    xs = [x for x, _ in table]
    position = bisect.bisect_left(xs, raw)
    if position == 0:
        return table[0][1]
    if position >= len(table):
        return table[-1][1]
    x0, y0 = table[position - 1]
    x1, y1 = table[position]
    return y1 if x1 == x0 else y0 + (y1 - y0) * (raw - x0) / (x1 - x0)


def _print_summary(r: dict) -> None:
    print("\n--- Resultats (donnees SIMULEES) ---")
    print(f"  AUC modele    : {r['auc_model']:.3f}")
    print(f"  AUC baseline  : {r['auc_baseline']:.3f}   (part du budget consommee)")
    print(f"  Ecart         : {r['auc_model'] - r['auc_baseline']:+.3f}")
    print(f"  Brier brut    : {r['brier_raw']:.4f}")
    print(f"  Brier calibre : {r['brier_calibrated']:.4f}")
    print(f"  Brier baseline: {r['brier_baseline']:.4f}")
    print("\n  Variables les plus utilisees :")
    for name, gain in r["importance"][:5]:
        print(f"    {name:<18} {gain:,.0f}")


def _report(r: dict) -> str:
    lines = [
        "# Risque de dépassement SLA — LightGBM contre règle (S7-J3)",
        "",
        "> ⚠ **Ces chiffres sont obtenus sur un historique SIMULÉ.** Aucun ticket du projet n'a",
        "> jamais été résolu : la colonne `resolved_at` a été ajoutée le jour même (V17), et le",
        "> corpus est synthétique. L'AUC ci-dessous mesure la capacité de LightGBM à retrouver les",
        "> règles du simulateur de `ml/train_sla_risk.py`. Elle ne dit **rien** de sa performance",
        "> sur des tickets réels, et ne doit pas être citée comme telle.",
        "",
        f"Échantillon : {r['n_train']} en apprentissage, {r['n_test']} en test. "
        f"Taux de dépassement observé : {r['breach_rate']:.1%}.",
        "",
        "## Pouvoir discriminant",
        "",
        "| Modèle | AUC | Brier |",
        "|---|---|---|",
        f"| Règle — part du budget consommée | {r['auc_baseline']:.3f} | {r['brier_baseline']:.4f} |",
        f"| LightGBM (score brut) | {r['auc_model']:.3f} | {r['brier_raw']:.4f} |",
        f"| LightGBM + calibration isotone | {r['auc_model']:.3f} | {r['brier_calibrated']:.4f} |",
        "",
        f"**Écart d'AUC modèle − règle : {r['auc_model'] - r['auc_baseline']:+.3f}.**",
        "",
        "La calibration ne change pas l'AUC — elle est monotone, donc elle préserve l'ordre. Elle",
        "n'agit que sur le Brier, c'est-à-dire sur la véracité du chiffre affiché. C'est exactement",
        "ce qu'on lui demande : le tri de la file vient de l'ordre, la phrase « 80 % de risque »",
        "vient de la calibration.",
        "",
        "## Variables les plus utilisées (gain)",
        "",
        "| Variable | Gain |",
        "|---|---|",
    ]
    lines += [f"| `{name}` | {gain:,.0f} |" for name, gain in r["importance"]]
    lines += [
        "",
        "## Courbe de fiabilité (après calibration)",
        "",
        "| Probabilité annoncée | Fréquence observée | Effectif |",
        "|---|---|---|",
    ]
    lines += [
        f"| {predicted:.2f} | {observed:.2f} | {count} |"
        for predicted, observed, count in r["reliability"]
    ]
    lines += [
        "",
        f"Part des tickets au-dessus du seuil d'attention ({AT_RISK_THRESHOLD:.0%}) : "
        f"**{r['at_risk_share']:.1%}**. Ce seuil n'est pas une propriété du modèle : il fixe la",
        "taille de la file prioritaire et devrait se régler sur la capacité de l'équipe.",
        "",
        "## Décision",
        "",
        "Voir `docs/adr/0010-risque-sla-modele-vs-regles.md`, dont les règles ont été **écrites",
        "avant** ces chiffres.",
        "",
    ]
    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())
