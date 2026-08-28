#!/usr/bin/env python3
"""Sonde de diagnostic du détecteur d'anomalies (S7-J2).

    docker compose exec ai-service python /eval/debug_anomaly.py

Le détecteur renvoie une liste d'anomalies, et rien quand il n'en trouve pas. C'est le bon contrat
pour un service, et le pire pour un diagnostic : « aucune anomalie » ne dit pas *pourquoi*.

Ce script rejoue exactement le même calcul et affiche les grandeurs intermédiaires — observé,
attendu, résidu, MAD, score, méthode — pour chaque catégorie et chaque heure examinée. Il ne fait
partie d'aucun chemin de production : il vit dans `eval/`, comme les autres harnesses lancés dans
le conteneur.
"""
from __future__ import annotations

import asyncio
import sys

sys.path.insert(0, "/srv")

from app.agents import insight_db  # noqa: E402
from app.anomaly import detect, series  # noqa: E402

LOOKBACK = 3


async def main() -> int:
    await insight_db.connect()
    if not insight_db.available():
        print("Pool insight_ro indisponible")
        return 1

    grid, per_category = await series.hourly_by_category(336)
    print(f"Grille : {len(grid)} heures, de {grid[0]} a {grid[-1]}")
    print(f"Categories : {sorted(per_category)}\n")

    for scope, values in sorted(per_category.items()):
        non_zero = sum(1 for v in values if v)
        print(f"--- {scope} : {sum(values)} tickets sur {non_zero} heures non vides")

        for offset in range(1, LOOKBACK + 1):
            position = len(values) - offset
            observed = values[position]
            line = f"    {grid[position]}  observe={observed:>3}"

            if observed < detect.MIN_ABSOLUTE:
                print(f"{line}  -> sous le plancher absolu ({detect.MIN_ABSOLUTE})")
                continue

            residuals, expected, method = detect._residuals(values, position)
            if residuals is None:
                print(f"{line}  -> {method}")
                continue

            from statistics import median

            centre = median(residuals)
            mad = median([abs(x - centre) for x in residuals])
            score = detect.robust_score(residuals, residuals[position])

            print(
                f"{line}  attendu={expected:>7.2f}  residu={residuals[position]:>7.2f}"
                f"  mediane={centre:>6.2f}  MAD={mad:>5.2f}  score={score:>7.2f}"
                f"  methode={method}"
            )
            if score < detect.WARNING_SCORE:
                print(f"        -> sous le seuil ({detect.WARNING_SCORE})")
            elif residuals[position] <= 0:
                print("        -> residu negatif : les chutes ne sont pas signalees")
            else:
                print("        -> ANOMALIE")
        print()

    await insight_db.disconnect()
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
