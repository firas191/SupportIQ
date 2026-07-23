"""Persistance des analyses de triage (S3-J3) via le pool asyncpg.

Écrit une ligne `analyses` par ticket (table créée par la migration Flyway V3 côté Spring).
Résilient : si la base est indisponible, on logue et on continue (le message RabbitMQ reste
acquitté — l'analyse a bien eu lieu, seule la persistance a échoué).
"""
import logging

from app.core import db
from app.schemas import AnalysisResult

logger = logging.getLogger(__name__)

_INSERT = """
    INSERT INTO analyses
        (ticket_id, priority, category, sentiment, keywords, confidence, model_used, escalated_to_llm)
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
    ON CONFLICT (ticket_id) DO NOTHING
"""


async def save_analysis(ticket_id: int | None, result: AnalysisResult) -> None:
    pool = db.pool()
    if ticket_id is None or pool is None:
        return
    try:
        async with pool.acquire() as conn:
            await conn.execute(
                _INSERT,
                ticket_id,
                result.priority.value,
                result.category.value,
                result.sentiment.value,
                result.keywords,
                result.confidence,
                result.model_used,
                result.escalated_to_llm,
            )
    except Exception as exc:  # noqa: BLE001
        logger.warning("Persistance de l'analyse échouée (ticket %s): %s", ticket_id, exc)
