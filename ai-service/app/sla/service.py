"""Scoring par lots du risque SLA (S7-J3).

Le score est **recalculé périodiquement et stocké**, plutôt que calculé à la lecture. Ce n'est pas
le choix évident — un score stocké vieillit — mais c'est le seul qui permette de **trier** la file
par risque : le tri et la pagination se font en SQL, sur une colonne, et une valeur calculée dans
l'application ne peut pas participer à un `ORDER BY ... LIMIT`.

La péremption est donc bornée par la période de l'ordonnanceur, et `computed_at` voyage jusqu'à
l'interface : afficher un score sans dire quand il a été calculé laisserait croire à une valeur
instantanée, alors que sa variable dominante est le temps restant.
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone

from app.core import db
from app.sla import features, model, store

logger = logging.getLogger(__name__)

#: Borne de coût. Le lot est tronqué, donc **l'ordre décide de ce qui est scoré**.
MAX_TICKETS = 5_000

#: Tickets ouverts, **les échéances les plus récentes d'abord**.
#:
#: L'ordre initial était l'inverse (`sla_due_at` croissant), et la première exécution sur données
#: réelles l'a invalidé : 5 000 tickets scorés, 5 000 « à risque ». Un indicateur qui désigne tout
#: le monde ne désigne personne.
#:
#: Le mécanisme : le plafond retenait les échéances les plus anciennes, donc les tickets les plus
#: en retard — ceux dont la part de budget consommée dépasse largement 1 et se plafonne à 1,0. Ce
#: sont exactement les moins informatifs, puisqu'ils ont *déjà* dépassé. Les tickets qui approchent
#: de leur échéance, les seuls sur lesquels on peut encore agir, n'étaient jamais atteints.
#:
#: Reste une limite de fond que ce tri ne corrige pas : « risque de dépasser » et « a déjà
#: dépassé » sont deux états distincts, que le plafonnement à 1,0 écrase l'un sur l'autre. Les
#: distinguer demande un état de ticket, pas un score — c'est un choix produit, noté et non fait.
_OPEN_TICKETS = """
    SELECT t.id, t.created_at, t.sla_due_at, t.source,
           a.priority, a.category, a.sentiment
    FROM tickets t
    LEFT JOIN analyses a ON a.ticket_id = t.id
    WHERE t.resolved_at IS NULL
      AND t.status <> 'MERGED'
    ORDER BY t.sla_due_at DESC NULLS LAST
    LIMIT $1
"""

#: Encombrement de la file par catégorie, à l'instant du calcul.
#:
#: Une seule requête agrégée, et non une sous-requête corrélée par ticket : sur 5 000 tickets, la
#: seconde forme ferait 5 000 comptages. La variable est définie comme « la file *actuelle* » et non
#: « la file à l'arrivée du ticket », pour que l'entraînement et le service parlent de la même
#: chose — c'est ce qui est simulable côté entraînement et calculable côté service.
_BACKLOG = """
    SELECT coalesce(a.category, 'NON_ANALYSE') AS category, COUNT(*) AS n
    FROM tickets t
    LEFT JOIN analyses a ON a.ticket_id = t.id
    WHERE t.resolved_at IS NULL AND t.status <> 'MERGED'
    GROUP BY 1
"""


async def score_open_tickets() -> dict:
    """Recalcule le risque de tous les tickets ouverts. Renvoie un compte rendu."""
    pool = db.pool()
    if pool is None:
        raise RuntimeError("Base de donnees indisponible")

    async with pool.acquire() as conn:
        rows = await conn.fetch(_OPEN_TICKETS, MAX_TICKETS)
        backlog = {r["category"]: int(r["n"]) for r in await conn.fetch(_BACKLOG)}

    now = datetime.now(timezone.utc)
    scored: list[tuple[int, float, str]] = []
    for row in rows:
        vector = features.build(
            {
                "created_at": row["created_at"],
                "sla_due_at": row["sla_due_at"],
                "priority": row["priority"],
                "category": row["category"],
                "sentiment": row["sentiment"],
                "source": row["source"],
                "backlog": backlog.get(row["category"] or "NON_ANALYSE", 0),
            },
            now,
        )
        risk, origin = model.score(vector, row["priority"])
        scored.append((row["id"], risk, origin))

    written = await store.replace(scored)
    origin = scored[0][2] if scored else ("lightgbm" if model.available() else "rules")
    logger.info("Risque SLA recalcule sur %d tickets (modele=%s)", written, origin)
    return {"scored": written, "model": origin, "at_risk": _count_at_risk(scored)}


def _count_at_risk(scored: list[tuple[int, float, str]]) -> int:
    """Tickets au-dessus du seuil d'attention.

    Le seuil n'est pas une propriété du modèle mais une **décision d'exploitation** : il fixe la
    taille de la file « à traiter en priorité », et il devrait se régler sur la capacité de
    l'équipe. 0,7 est un point de départ, pas une mesure — dit tel quel dans l'ADR-0010.
    """
    from app.config import settings

    return sum(1 for _, risk, _ in scored if risk >= settings.sla_at_risk_threshold)
