"""Détection d'anomalies de volume — orchestration (S7-J2).

**Ce service ne persiste rien.** Il mesure et renvoie des candidates ; c'est Spring qui décide d'en
faire des alertes, les déduplique et les diffuse.

Ce partage diffère de celui des sujets émergents (S7-J1), où FastAPI écrit sa table, et la
différence est délibérée : une alerte porte un **acquittement**, c'est-à-dire une décision humaine
attachée à un utilisateur identifié. Tout ce qui a un cycle de vie humain vit du côté du plan de
contrôle, qui a l'authentification, le RBAC et les transactions. Un instantané de sujets n'a pas de
cycle de vie : il est calculé, il est lu, il est remplacé.
"""
from __future__ import annotations

import logging

from app.agents import insight_db
from app.anomaly import detect, series

logger = logging.getLogger(__name__)

#: Fenêtre d'historique. Deux semaines = 336 points horaires, soit 14 observations par phase
#: horaire : assez pour estimer une forme saisonnière sans remonter à une période où le produit
#: était différent.
DEFAULT_WINDOW_HOURS = 336


async def run(window_hours: int = DEFAULT_WINDOW_HOURS, lookback: int = 1) -> dict:
    """Mesure les dernières heures et renvoie les pics constatés."""
    if not insight_db.available():
        raise RuntimeError("Acces en lecture seule indisponible")

    grid, per_category = await series.hourly_by_category(window_hours)
    if not grid:
        return {"window_hours": window_hours, "categories": 0, "anomalies": []}

    anomalies = detect.scan(grid, per_category, lookback=lookback)
    logger.info(
        "Detection d'anomalies: %d categories examinees, %d pics",
        len(per_category), len(anomalies),
    )

    return {
        "window_hours": window_hours,
        "categories": len(per_category),
        "anomalies": [
            {
                "scope": a.scope,
                "bucket_start": a.bucket_start.isoformat(),
                "severity": a.severity,
                "observed": a.observed,
                "expected": round(a.expected, 2),
                "score": round(a.score, 2),
                "method": a.method,
                "payload": a.payload(),
            }
            for a in anomalies
        ],
    }
