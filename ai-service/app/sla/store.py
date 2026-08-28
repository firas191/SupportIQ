"""Persistance des scores de risque SLA (S7-J3)."""
from __future__ import annotations

import logging
from decimal import Decimal

from app.core import db

logger = logging.getLogger(__name__)


async def replace(scored: list[tuple[int, float, str]]) -> int:
    """Écrit les scores du lot. Renvoie le nombre de lignes écrites.

    **UPSERT et non remplacement transactionnel**, contrairement aux fragments de la base de
    connaissances (S5-J1). La différence est réelle : là-bas un document réécrit pouvait avoir
    *moins* de fragments qu'avant, et les surnuméraires seraient restés indexés. Ici la clé est le
    ticket, elle ne disparaît pas d'un lot à l'autre — sauf quand le ticket est résolu, et
    `ON DELETE CASCADE` s'en charge quand il est supprimé.

    Reste un cas : un ticket **résolu** garde son dernier score. C'est voulu — la file de travail ne
    montre que les tickets ouverts, et effacer le score priverait d'un futur post-mortem (« le
    modèle avait-il vu venir ce dépassement ? »).
    """
    pool = db.pool()
    if pool is None or not scored:
        return 0

    payload = [(ticket_id, Decimal(f"{risk:.3f}"), origin) for ticket_id, risk, origin in scored]

    try:
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.executemany(
                    """
                    INSERT INTO sla_risks (ticket_id, risk, model, computed_at)
                    VALUES ($1, $2, $3, now())
                    ON CONFLICT (ticket_id) DO UPDATE
                    SET risk = EXCLUDED.risk,
                        model = EXCLUDED.model,
                        computed_at = EXCLUDED.computed_at
                    """,
                    payload,
                )
    except Exception as exc:  # noqa: BLE001 - la file reste utilisable sans score frais
        logger.warning("Persistance des risques SLA echouee: %s", exc)
        return 0

    return len(payload)
