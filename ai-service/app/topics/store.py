"""Lecture des tickets à regrouper et écriture de l'instantané (S7-J1)."""
from __future__ import annotations

import logging
from decimal import Decimal

from app.core import db

logger = logging.getLogger(__name__)


async def load_window(days: int, limit: int) -> list[dict]:
    """Tickets récents **déjà embeddés**, du plus ancien au plus récent.

    La jointure sur `embeddings` est interne, pas externe : un ticket sans vecteur n'a pas de
    position dans l'espace sémantique, donc aucune place dans un regroupement. L'écarter est le
    seul traitement correct — et l'écart entre le nombre de tickets de la fenêtre et le nombre
    embeddé est remonté au service, qui le journalise : si le rattrapage d'embeddings n'a jamais
    tourné, on regrouperait quelques dizaines de tickets en croyant en analyser des milliers.

    `limit` borne le coût : UMAP est quadratique en mémoire sur les voisinages, et un instantané
    nocturne n'a pas à traiter cent mille tickets pour être utile. On prend les plus récents.
    """
    pool = db.pool()
    if pool is None:
        return []

    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT t.id, t.subject, t.body, t.created_at, a.category, e.vector::text AS vector
            FROM tickets t
            JOIN embeddings e ON e.ticket_id = t.id
            LEFT JOIN analyses a ON a.ticket_id = t.id
            WHERE t.created_at >= now() - make_interval(days => $1)
              AND t.status <> 'MERGED'
            ORDER BY t.created_at DESC
            LIMIT $2
            """,
            days, limit,
        )

    # Remis dans l'ordre chronologique : la moitié « récente » de la fenêtre doit être la fin.
    return [
        {
            "id": r["id"],
            "subject": r["subject"],
            "body": r["body"],
            "created_at": r["created_at"],
            "category": r["category"],
            "vector": _parse_vector(r["vector"]),
        }
        for r in reversed(rows)
    ]


async def count_window(days: int) -> int:
    """Tickets de la fenêtre, embeddés ou non — sert à mesurer la couverture."""
    pool = db.pool()
    if pool is None:
        return 0
    async with pool.acquire() as conn:
        value = await conn.fetchval(
            "SELECT COUNT(*) FROM tickets "
            "WHERE created_at >= now() - make_interval(days => $1) AND status <> 'MERGED'",
            days,
        )
    return int(value or 0)


def _parse_vector(raw: str) -> list[float]:
    """`vector` est rendu en texte (`[0.1,0.2,…]`) : asyncpg ne connaît pas le type pgvector.

    Le convertir côté SQL avec `::text` puis le relire ici évite d'ajouter le paquet `pgvector`
    pour une seule lecture. Même arbitrage qu'à l'écriture (S3-J4), qui passe par un littéral.
    """
    return [float(x) for x in raw.strip("[]").split(",") if x]


async def save_snapshot(window_days: int, topics: list[dict]) -> int:
    """Écrit un instantané complet. Renvoie le nombre de sujets enregistrés.

    **Aucune suppression.** Les instantanés s'empilent, et la lecture ne retient que le dernier
    (`computed_at` le plus récent). Conserver l'historique coûte quelques lignes par exécution et
    permettra un jour de montrer qu'un sujet a occupé l'équipe trois semaines — ce que l'écran
    d'aujourd'hui ne fait pas, mais qu'une donnée effacée rendrait impossible à faire plus tard.
    """
    pool = db.pool()
    if pool is None or not topics:
        return 0

    async with pool.acquire() as conn:
        async with conn.transaction():
            # Une seule valeur de `computed_at` pour tout le lot : c'est elle qui fait l'unité de
            # l'instantané. La laisser au DEFAULT donnerait des horodatages qui diffèrent de
            # quelques millisecondes, et « le dernier instantané » ne serait plus une requête.
            stamp = await conn.fetchval("SELECT now()")
            for topic in topics:
                await conn.execute(
                    """
                    INSERT INTO topics (computed_at, window_days, label, size,
                                        recent_count, previous_count, growth,
                                        sample_ticket_ids, top_category)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                    """,
                    stamp, window_days, topic["label"], topic["size"],
                    topic["recent_count"], topic["previous_count"],
                    # `growth` est un NUMERIC : asyncpg refuse un flottant Python dessus (même
                    # correctif qu'au S5-J5 sur `judge_score`). `None` reste `None` : un sujet
                    # nouveau n'a pas de croissance chiffrable.
                    None if topic["growth"] is None else Decimal(str(topic["growth"])),
                    topic["sample_ticket_ids"], topic["top_category"],
                )
    logger.info("Instantane de sujets enregistre: %d sujets", len(topics))
    return len(topics)
