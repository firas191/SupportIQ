"""Orchestration de la base de connaissances (S5-J1).

Chaîne d'ingestion : **lecture → découpage sémantique → embedding → persistance**.

Trois garanties de conception :

1. **Idempotence.** Un document ré-importé remplace intégralement ses fragments (clé : le nom de
   fichier). Ré-importer une FAQ corrigée ne laisse aucun paragraphe périmé indexé.

2. **Dégradation propre.** Si le modèle d'embeddings n'est pas chargé (première exécution,
   téléchargement en cours, CI sans modèle), les fragments sont **quand même stockés**, simplement
   sans vecteur. L'écran d'administration l'affiche, et une ré-indexation les rattrape. L'inverse —
   refuser l'import — perdrait le travail de découpage pour une raison temporaire.

3. **Requête et document ne s'embeddent pas pareil.** e5 est asymétrique : les fragments sont
   indexés avec le préfixe « passage », les questions interrogées avec « query ». C'est ce que le
   modèle a vu à l'entraînement.
"""
from __future__ import annotations

import logging

from app.kb import lexical, retrieval, store
from app.kb.chunker import chunk_document
from app.kb.loader import load
from app.pipeline import embeddings

logger = logging.getLogger(__name__)


async def ingest(filename: str, data: bytes) -> dict:
    """Indexe un document. Lève `UnsupportedDocument` si le fichier est illisible."""
    document = load(filename, data)
    chunks = chunk_document(document.text)

    rows = []
    embedded = 0
    for chunk in chunks:
        # Le titre de section est inclus dans le texte embeddé : « Facturation > Remboursement »
        # apporte un contexte que le fragment seul n'a pas toujours.
        vector = embeddings.embed(chunk.embedding_text(), prefix="passage")
        if vector:
            embedded += 1
        rows.append(
            {
                "chunk_index": chunk.index,
                "heading": chunk.heading,
                "content": chunk.content,
                "vector": vector,
            }
        )

    stored = await store.replace_document(filename, document.title, rows)
    # L'index lexical vit en memoire : sans cette invalidation, un document tout juste importe
    # serait trouvable par le vecteur mais invisible a BM25 jusqu'au prochain redemarrage.
    lexical.index.invalidate()
    logger.info(
        "KB: %s indexe (%d fragments, %d vectorises)", filename, stored, embedded
    )
    return {
        "source": filename,
        "title": document.title,
        "chunks": stored,
        "indexed": embedded,
        "characters": len(document.text),
    }


async def documents() -> list[dict]:
    return await store.list_documents()


async def remove(source: str) -> int:
    deleted = await store.delete_document(source)
    lexical.index.invalidate()
    return deleted


async def reindex(force: bool = False) -> dict:
    """Recalcule les vecteurs.

    `force=False` (défaut) ne traite que les fragments sans vecteur — c'est le rattrapage après un
    import fait modèle indisponible, et c'est rapide.

    `force=True` recalcule **tout** : nécessaire uniquement après un changement de modèle
    d'embeddings, car mélanger deux modèles dans le même index rend les distances incomparables.
    """
    targets = await (store.all_chunks() if force else store.chunks_without_vector())
    if not targets:
        return {"processed": 0, "failed": 0}

    processed = 0
    failed = 0
    for chunk in targets:
        text = f"{chunk['heading']}\n{chunk['content']}" if chunk["heading"] else chunk["content"]
        vector = embeddings.embed(text, prefix="passage")
        if vector is None:
            failed += 1
            continue
        await store.set_vector(chunk["id"], vector)
        processed += 1

    lexical.index.invalidate()
    logger.info("KB: reindexation terminee (%d traites, %d echecs)", processed, failed)
    return {"processed": processed, "failed": failed}


async def search(
    question: str,
    k: int = 5,
    mode: retrieval.SearchMode = "hybrid",
) -> list[dict]:
    """Recherche dans la base de connaissances.

    Depuis le J2, le mode par defaut est **hybride** : vecteurs + BM25, fusion RRF, puis reranking
    par cross-encodeur. Le mode `vector` (comportement du J1) reste accessible — il sert de point de
    comparaison au harness d'evaluation, et permet a l'ecran d'administration de montrer l'ecart.
    """
    return await retrieval.search(question, k=k, mode=mode)
