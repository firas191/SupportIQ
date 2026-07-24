"""Pool de connexions PostgreSQL (asyncpg).

Le service IA lit/ecrit dans la meme base que Spring (pgvector) pour les embeddings et
analyses (a partir de la semaine 3). On distingue :
- liveness (/health) : le process repond, independamment de la base ;
- readiness (/health/ready) : la base est joignable (SELECT 1).

Le demarrage est resilient : si PostgreSQL est indisponible, le service demarre quand meme
et la readiness le signalera (utile en orchestration et pour ne pas coupler la CI a une base).
"""
import logging

import asyncpg

from app.config import settings

logger = logging.getLogger(__name__)

_pool: asyncpg.Pool | None = None


def _dsn() -> str:
    # asyncpg attend un DSN 'postgresql://', sans le suffixe de dialecte SQLAlchemy '+asyncpg'.
    return settings.database_url.replace("postgresql+asyncpg://", "postgresql://")


async def connect() -> None:
    global _pool
    try:
        _pool = await asyncpg.create_pool(dsn=_dsn(), min_size=1, max_size=5, timeout=10)
        logger.info("Pool PostgreSQL initialise")
    except Exception as exc:  # noqa: BLE001 - base indisponible : on ne bloque pas le demarrage
        _pool = None
        logger.warning("PostgreSQL injoignable au demarrage: %s", exc)


async def disconnect() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None


def pool() -> asyncpg.Pool | None:
    """Pool courant (None si la base est indisponible). Utilisé par la persistance des analyses."""
    return _pool


async def ping() -> bool:
    if _pool is None:
        return False
    try:
        async with _pool.acquire() as conn:
            await conn.fetchval("SELECT 1")
        return True
    except Exception:  # noqa: BLE001 - readiness : toute erreur = base non prête
        return False
