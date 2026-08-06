"""Persistance des brouillons de réponse (S5-J3).

Écrit dans `draft_responses`, table créée par Flyway côté Spring (V9) — même frontière que pour
`analyses`, `embeddings` et `kb_documents`.

Aucune mise à jour en place : chaque exécution de l'agent **ajoute** une ligne. L'historique des
re-générations est conservé, exactement comme `annotations` conserve l'historique des corrections
humaines (S4-J4). C'est ce qui permettra de mesurer, en S5-J5, combien de brouillons ont dû être
regénérés ou rejetés — un taux qu'on ne peut pas calculer si l'on écrase.
"""
from __future__ import annotations

import json
import logging

from app.core import db

logger = logging.getLogger(__name__)


async def save(
    ticket_id: int,
    content: str,
    citations: list[dict],
    tone: str,
    low_confidence: bool,
    issues: list[str],
    attempts: int,
) -> int | None:
    """Enregistre un brouillon. Renvoie son identifiant, ou None si la base est absente."""
    pool = db.pool()
    if pool is None:
        return None
    try:
        async with pool.acquire() as conn:
            return await conn.fetchval(
                """
                INSERT INTO draft_responses
                    (ticket_id, content, citations, tone, low_confidence, issues, attempts)
                VALUES ($1, $2, $3::jsonb, $4, $5, $6, $7)
                RETURNING id
                """,
                ticket_id,
                content,
                json.dumps(citations, ensure_ascii=False),
                tone,
                low_confidence,
                issues,
                attempts,
            )
    except Exception as exc:  # noqa: BLE001
        # Un brouillon non persisté reste utilisable : il est renvoyé dans la réponse HTTP.
        # Perdre l'historique est ennuyeux, perdre le brouillon serait pire.
        logger.warning("Persistance du brouillon echouee (ticket %s): %s", ticket_id, exc)
        return None


async def ticket_context(ticket_id: int) -> dict | None:
    """Le ticket et son analyse, tels qu'ils alimentent le prompt.

    La catégorie et l'humeur viennent de `analyses` : elles ne servent pas à chercher, mais à
    **cadrer le ton**. Un client mécontent sur une réclamation n'appelle pas la même formulation
    qu'une demande d'information neutre.
    """
    pool = db.pool()
    if pool is None:
        return None
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            """
            SELECT t.id, t.subject, t.body, t.language, t.customer_email,
                   a.category, a.sentiment, a.priority
            FROM tickets t
            LEFT JOIN analyses a ON a.ticket_id = t.id
            WHERE t.id = $1
            """,
            ticket_id,
        )
    return dict(row) if row else None
