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

from app.kb import store
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
    return await store.delete_document(source)


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

    logger.info("KB: reindexation terminee (%d traites, %d echecs)", processed, failed)
    return {"processed": processed, "failed": failed}


async def search(question: str, k: int = 5) -> list[dict]:
    """Recherche vectorielle dans la KB — le « KB interrogeable » attendu au J1.

    Le retrieval **hybride** (BM25 + vecteurs, fusion RRF, reranking cross-encoder) est le sujet du
    J2 : ici on pose la brique vectorielle seule, ce qui donne un point de comparaison chiffré pour
    mesurer l'apport réel de l'hybride au J2.
    """
    vector = embeddings.embed(question, prefix="query")
    if vector is None:
        return []
    return await store.search(vector, k)
