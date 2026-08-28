"""Construction des séries horaires de volume (S7-J2).

Lecture sous le rôle **`insight_ro`** : ce détecteur ne fait que lire, il n'a aucune raison de
disposer d'un accès en écriture. Même choix qu'au digest (S6-J4).

Le travail réel de ce module tient en une ligne de code et une page de justification : **remplir les
heures vides**. La vue `v_hourly_volume` n'a pas de ligne pour une heure sans ticket ; utiliser ses
lignes telles quelles reviendrait à calculer la « normale » d'une catégorie sur ses seules heures
actives. Une catégorie qui reçoit 4 tickets à 10 h et rien du reste de la journée aurait alors une
moyenne de 4 et une variabilité nulle — et paraîtrait d'une régularité parfaite alors qu'elle est en
réalité presque toujours à zéro. Les trois tickets de 3 h du matin deviendraient une alerte.
"""
from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from app.agents import insight_db

logger = logging.getLogger(__name__)

_SQL = """
    SELECT bucket, category, tickets
    FROM v_hourly_volume
    WHERE bucket >= $1 AND bucket < $2
    ORDER BY bucket
"""


def floor_hour(moment: datetime) -> datetime:
    """Début de l'heure contenant `moment`, en UTC. Unité de mesure du détecteur."""
    return moment.astimezone(timezone.utc).replace(minute=0, second=0, microsecond=0)


async def hourly_by_category(
    window_hours: int, now: datetime | None = None
) -> tuple[list[datetime], dict[str, list[int]]]:
    """Grille horaire complète et, pour chaque catégorie, sa série alignée dessus.

    L'heure en cours est **exclue** : elle est incomplète par construction, et la comparer à des
    heures pleines produirait une chute apparente à chaque passage du détecteur. C'est le genre de
    faux positif qui se déclenche à intervalle régulier, donc celui qu'on apprend le plus vite à
    ignorer.
    """
    end = floor_hour(now or datetime.now(timezone.utc))
    start = end - timedelta(hours=window_hours)

    # `json_safe=False` : on veut des `datetime`, pas des chaines ISO. La conversion par defaut
    # existe pour l'agent Insight, dont le resultat part en JSON vers un client HTTP ; ici on
    # relit `bucket` pour le placer dans une grille horaire, et une chaine n'a pas de fuseau.
    _, rows = await insight_db.run_query_args(_SQL, start, end, json_safe=False)

    grid = [start + timedelta(hours=i) for i in range(window_hours)]
    index = {moment: position for position, moment in enumerate(grid)}

    series: dict[str, list[int]] = {}
    for bucket, category, tickets in rows:
        position = index.get(floor_hour(bucket))
        if position is None:
            continue  # bordure d'arrondi : hors grille, on ignore plutot que de decaler
        series.setdefault(category, [0] * window_hours)[position] = int(tickets or 0)

    logger.info(
        "Serie horaire: %d heures, %d categories, %d lignes lues",
        window_hours, len(series), len(rows),
    )
    return grid, series
