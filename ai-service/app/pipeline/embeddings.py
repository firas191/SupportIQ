"""Embeddings vectoriels + recherche de tickets similaires (S3-J4).

Modèle **multilingual-e5** (FR+EN) via sentence-transformers, vecteurs stockés dans **pgvector**
(index HNSW). La similarité est la distance cosinus pgvector (`<=>`). Règle de doublon : deux tickets
de **même catégorie** avec un cosinus ≥ `duplicate_threshold` (0.92) sont candidats à la fusion.

Chargement paresseux et résilient : si le modèle ou la base manquent, les fonctions renvoient None/[]
sans casser le pipeline (les nouveaux tickets sont juste analysés sans embedding).
"""
import logging

from app.config import settings
from app.core import db

logger = logging.getLogger(__name__)

_model = None
_load_failed = False


def _load() -> None:
    global _model, _load_failed
    if _model is not None or _load_failed:
        return
    try:
        from sentence_transformers import SentenceTransformer

        _model = SentenceTransformer(settings.embedding_model)
        logger.info("Modele d'embeddings charge: %s", settings.embedding_model)
    except Exception as exc:  # noqa: BLE001
        _load_failed = True
        logger.warning("Modele d'embeddings indisponible (%s) - similarite desactivee", exc)


def get_model():
    """Expose le SentenceTransformer chargé (réutilisé par KeyBERT — un seul modèle en mémoire)."""
    _load()
    return _model


def embed(text: str) -> list[float] | None:
    _load()
    if _model is None:
        return None
    # e5 attend un préfixe de tâche ; "query: " suffit pour une similarité ticket↔ticket symétrique.
    vector = _model.encode("query: " + text, normalize_embeddings=True)
    return vector.tolist()


def _to_pgvector(vector: list[float]) -> str:
    return "[" + ",".join(f"{x:.6f}" for x in vector) + "]"


async def store_embedding(ticket_id: int | None, text: str) -> None:
    if ticket_id is None:
        return
    vector = embed(text)
    pool = db.pool()
    if vector is None or pool is None:
        return
    try:
        async with pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO embeddings (ticket_id, vector, model)
                VALUES ($1, $2::vector, $3)
                ON CONFLICT (ticket_id) DO UPDATE SET vector = EXCLUDED.vector, model = EXCLUDED.model
                """,
                ticket_id, _to_pgvector(vector), settings.embedding_model,
            )
    except Exception as exc:  # noqa: BLE001
        logger.warning("Persistance embedding echouee (ticket %s): %s", ticket_id, exc)


async def find_similar(ticket_id: int | None, text: str | None, k: int = 5) -> list[dict]:
    """Top-k tickets proches. Chaque résultat porte un drapeau `is_duplicate` (même catégorie + cosinus élevé)."""
    pool = db.pool()
    if pool is None:
        return []
    threshold = settings.duplicate_threshold

    async with pool.acquire() as conn:
        # ef_search au niveau SESSION (fiable, exactement comme un SET psql — le SET LOCAL en
        # transaction asyncpg ne s'appliquait pas au planner). Sinon le HNSW rate des voisins
        # sur un corpus très redondant (clusters de doublons). Compromis recall/latence.
        await conn.execute(f"SET hnsw.ef_search = {int(settings.hnsw_ef_search)}")

        query_category = None
        if ticket_id is not None:
            # Existence de l'embedding + catégorie du ticket requête (pour la règle de doublon).
            head = await conn.fetchrow(
                "SELECT a.category FROM embeddings e "
                "LEFT JOIN analyses a ON a.ticket_id = e.ticket_id WHERE e.ticket_id = $1",
                ticket_id,
            )
            if head is None:
                return []  # ticket pas encore embeddé
            query_category = head["category"]
            # Vecteur natif via sous-requête : AUCUN aller-retour texte (fidèle au SQL prouvé).
            rows = await conn.fetch(
                """
                SELECT e.ticket_id, t.subject, a.category,
                       1 - (e.vector <=> q.vector) AS similarity
                FROM embeddings e
                JOIN tickets t ON t.id = e.ticket_id
                LEFT JOIN analyses a ON a.ticket_id = e.ticket_id,
                     (SELECT vector FROM embeddings WHERE ticket_id = $1) q
                WHERE e.ticket_id <> $1
                ORDER BY e.vector <=> q.vector
                LIMIT $2
                """,
                ticket_id, k,
            )
        elif text is not None:
            vector = embed(text)
            if vector is None:
                return []
            rows = await conn.fetch(
                """
                SELECT e.ticket_id, t.subject, a.category,
                       1 - (e.vector <=> $1::vector) AS similarity
                FROM embeddings e
                JOIN tickets t ON t.id = e.ticket_id
                LEFT JOIN analyses a ON a.ticket_id = e.ticket_id
                ORDER BY e.vector <=> $1::vector
                LIMIT $2
                """,
                _to_pgvector(vector), k,
            )
        else:
            return []

    results = []
    for r in rows:
        similarity = float(r["similarity"])
        same_category = query_category is not None and r["category"] == query_category
        results.append({
            "ticket_id": r["ticket_id"],
            "subject": r["subject"],
            "category": r["category"],
            "similarity": round(similarity, 4),
            "is_duplicate": similarity >= threshold and same_category,
        })
    return results


async def backfill() -> int:
    """Embedde tous les tickets qui n'ont pas encore de vecteur (démo/rattrapage). Renvoie le compte."""
    pool = db.pool()
    if pool is None:
        return 0
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT t.id, t.subject, t.body
            FROM tickets t
            LEFT JOIN embeddings e ON e.ticket_id = t.id
            WHERE e.ticket_id IS NULL
            """
        )
    count = 0
    for r in rows:
        text = f"{r['subject'] or ''}\n\n{r['body'] or ''}".strip() or (r["subject"] or "")
        if not text:
            continue
        await store_embedding(r["id"], text)
        count += 1
    logger.info("Backfill embeddings: %d tickets traites", count)
    return count
