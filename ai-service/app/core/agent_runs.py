"""Persistance du journal des exécutions d'agents (S6-J5).

Écrit dans `agent_runs`, table créée par Flyway côté Spring (V14) — même frontière que pour
`analyses`, `embeddings`, `kb_documents` et `draft_responses`.

**Résilient par construction.** Une trace qui fait échouer le travail qu'elle observe est pire
qu'une absence de trace : elle transforme un incident d'observabilité en incident de production.
Toutes les erreurs sont donc avalées ici, et seulement ici.
"""
from __future__ import annotations

import logging

from app.core import db
from app.core.run_context import AgentRun

logger = logging.getLogger(__name__)


async def save(run: AgentRun) -> None:
    """Enregistre un run terminé. Sans effet si la base est indisponible."""
    pool = db.pool()
    if pool is None:
        return

    try:
        async with pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO agent_runs
                    (agent, ticket_id, calls, prompt_tokens, completion_tokens, duration_ms,
                     model_used, degraded, error)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                """,
                run.agent,
                run.ticket_id,
                run.calls,
                run.prompt_tokens,
                run.completion_tokens,
                run.duration_ms,
                run.model_used,
                run.degraded,
                run.error,
            )
    except Exception as exc:  # noqa: BLE001 - base absente, colonne manquante, migration en retard
        logger.warning("Journalisation du run %s echouee: %s", run.agent, exc)
