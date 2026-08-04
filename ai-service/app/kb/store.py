"""Persistance et recherche de la base de connaissances (S5-J1).

Écrit dans `kb_documents`, table créée par Flyway côté Spring (V8). Même séparation qu'en S3 pour
`analyses` et `embeddings` : le **schéma** appartient au plan de contrôle, le **calcul vectoriel**
au plan de calcul.

Toutes les fonctions sont résilientes à une base absente (`pool()` à None) : elles renvoient une
valeur vide plutôt que de lever. Le service IA doit démarrer et répondre `/health` même sans
PostgreSQL — c'est ce qui permet à la CI de tourner sans base.
"""
from __future__ import annotations

import logging

from app.config import settings
from app.core import db
from app.pipeline.embeddings import to_pgvector

logger = logging.getLogger(__name__)


async def replace_document(source: str, title: str, rows: list[dict]) -> int:
    """Remplace **tous** les fragments d'un document, dans une seule transaction.

    Le remplacement est un `DELETE` puis un `INSERT`, pas un `UPSERT` fragment par fragment : un
    document ré-importé après réécriture peut contenir **moins** de fragments qu'avant, et les
    anciens surnuméraires resteraient indexés. On aurait alors des réponses citant un paragraphe
    qui n'existe plus — le pire cas pour un RAG.

    La transaction garantit qu'on ne se retrouve jamais avec un document à moitié supprimé si
    l'insertion échoue.
    """
    pool = db.pool()
    if pool is None:
        return 0

    async with pool.acquire() as conn, conn.transaction():
        await conn.execute("DELETE FROM kb_documents WHERE source = $1", source)
        if not rows:
            return 0
        await conn.executemany(
            """
            INSERT INTO kb_documents (title, source, chunk_index, heading, content, vector, model)
            VALUES ($1, $2, $3, $4, $5, $6::vector, $7)
            """,
            [
                (
                    title,
                    source,
                    r["chunk_index"],
                    r["heading"],
                    r["content"],
                    to_pgvector(r["vector"]) if r["vector"] else None,
                    settings.embedding_model if r["vector"] else None,
                )
                for r in rows
            ],
        )
    return len(rows)


async def list_documents() -> list[dict]:
    """Un enregistrement par **document** (et non par fragment) : c'est l'unité que l'admin gère.

    `COUNT(vector)` compte les vecteurs non nuls : l'écart avec `COUNT(*)` signale les fragments
    non embeddés (modèle indisponible au moment de l'import) et donc invisibles à la recherche.
    C'est exactement ce qu'un administrateur doit voir pour savoir s'il faut ré-indexer.
    """
    pool = db.pool()
    if pool is None:
        return []
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT source,
                   MIN(title)          AS title,
                   COUNT(*)            AS chunks,
                   COUNT(vector)       AS indexed,
                   MAX(updated_at)     AS updated_at
            FROM kb_documents
            GROUP BY source
            ORDER BY MAX(updated_at) DESC
            """
        )
    return [
        {
            "source": r["source"],
            "title": r["title"],
            "chunks": r["chunks"],
            "indexed": r["indexed"],
            "updated_at": r["updated_at"].isoformat() if r["updated_at"] else None,
        }
        for r in rows
    ]


async def delete_document(source: str) -> int:
    pool = db.pool()
    if pool is None:
        return 0
    async with pool.acquire() as conn:
        result = await conn.execute("DELETE FROM kb_documents WHERE source = $1", source)
    # asyncpg renvoie « DELETE <n> » : le compte est le dernier champ.
    return int(result.rsplit(" ", 1)[-1] or 0)


async def search(vector: list[float], k: int) -> list[dict]:
    """Top-k fragments les plus proches (cosinus pgvector).

    Le `WHERE vector IS NOT NULL` n'est pas décoratif : un fragment non embeddé serait sinon classé
    par l'opérateur avec une distance indéfinie et pourrait remonter en tête.
    """
    pool = db.pool()
    if pool is None:
        return []

    async with pool.acquire() as conn:
        # Même réglage qu'en S3-J4 : le HNSW rate des voisins avec l'`ef_search` par défaut sur un
        # corpus redondant (une FAQ répète beaucoup de formulations d'une section à l'autre).
        await conn.execute(f"SET hnsw.ef_search = {int(settings.hnsw_ef_search)}")
        rows = await conn.fetch(
            """
            SELECT id, title, source, chunk_index, heading, content,
                   1 - (vector <=> $1::vector) AS similarity
            FROM kb_documents
            WHERE vector IS NOT NULL
            ORDER BY vector <=> $1::vector
            LIMIT $2
            """,
            to_pgvector(vector), k,
        )

    return [
        {
            "id": r["id"],
            "title": r["title"],
            "source": r["source"],
            "chunk_index": r["chunk_index"],
            "heading": r["heading"],
            "content": r["content"],
            "similarity": round(float(r["similarity"]), 4),
        }
        for r in rows
    ]


async def chunks_without_vector() -> list[dict]:
    """Fragments à (ré)embedder — utilisé par la ré-indexation."""
    pool = db.pool()
    if pool is None:
        return []
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, heading, content FROM kb_documents WHERE vector IS NULL ORDER BY id"
        )
    return [{"id": r["id"], "heading": r["heading"], "content": r["content"]} for r in rows]


async def all_chunks() -> list[dict]:
    pool = db.pool()
    if pool is None:
        return []
    async with pool.acquire() as conn:
        rows = await conn.fetch("SELECT id, heading, content FROM kb_documents ORDER BY id")
    return [{"id": r["id"], "heading": r["heading"], "content": r["content"]} for r in rows]


async def set_vector(chunk_id: int, vector: list[float]) -> None:
    pool = db.pool()
    if pool is None:
        return
    async with pool.acquire() as conn:
        await conn.execute(
            "UPDATE kb_documents SET vector = $2::vector, model = $3, updated_at = now() WHERE id = $1",
            chunk_id, to_pgvector(vector), settings.embedding_model,
        )
