"""Retrieval hybride : vecteurs + BM25, fusion RRF, reranking (S5-J2).

Chaîne complète :

```
question ──┬─→ recherche vectorielle (e5 + pgvector)  ─┐
           └─→ recherche lexicale (BM25)              ─┴─→ fusion RRF ─→ reranking ─→ top-k
```

**Pourquoi la fusion RRF et pas une moyenne pondérée des scores.** C'est le point non évident du
jour. Les deux moteurs produisent des scores qui ne vivent pas dans le même monde : un cosinus est
borné dans [0, 1] et se concentre en pratique entre 0,75 et 0,92 ; un score BM25 n'est borné par
rien et dépend de la taille du corpus et de la rareté des termes. Les combiner par
`a x cosinus + (1-a) x bm25` demanderait de normaliser — donc de choisir une échelle, qui
changerait à chaque évolution du corpus, et un poids `a` à recalibrer sans arrêt.

*Reciprocal Rank Fusion* règle le problème en jetant les scores et en ne gardant que le **rang** :

    score(d) = somme sur les moteurs i de  1 / (k + rang_i(d))

Un document 1ᵉʳ chez l'un et 8ᵉ chez l'autre bat un document 3ᵉ partout. La constante `k` (60 par
convention, issue de l'article original de Cormack et al.) amortit le poids des toutes premières
positions : sans elle, le 1ᵉʳ vaudrait deux fois le 2ᵉ, ce qui rendrait la fusion instable.

**Pourquoi un reranking par-dessus.** Le bi-encodeur encode question et fragment *séparément* : il
est rapide (les vecteurs des fragments sont précalculés) mais il ne peut pas confronter les deux
textes. Un **cross-encodeur** les lit ensemble et produit un score de pertinence bien plus
discriminant — c'est ce qui règle la compression observée au J1 (87 / 82 / 82 / 81 %). Il est
beaucoup plus coûteux, donc on ne le passe que sur les quelques candidats déjà retenus : c'est
l'intérêt de l'architecture en deux étages, rappel large puis précision.
"""
from __future__ import annotations

import logging
from typing import Literal

from app.config import settings
from app.kb import lexical, store
from app.pipeline import embeddings

logger = logging.getLogger(__name__)

SearchMode = Literal["vector", "hybrid"]


async def vector_search(question: str, k: int) -> list[dict]:
    vector = embeddings.embed(question, prefix="query")
    if vector is None:
        return []
    return await store.search(vector, k)


def reciprocal_rank_fusion(
    ranked_lists: list[list[dict]], k: int = 60, key: str = "id"
) -> list[dict]:
    """Fusionne plusieurs listes classées en une seule.

    Ne lit que la **position** dans chaque liste, jamais les scores : c'est ce qui rend la fusion
    insensible aux échelles hétérogènes des moteurs. Fonction pure, donc testable sans base ni
    modèle.
    """
    scores: dict[object, float] = {}
    documents: dict[object, dict] = {}

    for ranked in ranked_lists:
        for rank, doc in enumerate(ranked, start=1):
            doc_key = doc[key]
            scores[doc_key] = scores.get(doc_key, 0.0) + 1.0 / (k + rank)
            # On garde la première version rencontrée : les deux moteurs renvoient le même
            # fragment, seuls leurs champs de score diffèrent.
            documents.setdefault(doc_key, doc)

    ordered = sorted(scores.items(), key=lambda kv: kv[1], reverse=True)
    return [{**documents[doc_key], "fusion_score": round(score, 6)} for doc_key, score in ordered]


async def search(
    question: str,
    k: int = 5,
    mode: SearchMode = "hybrid",
    rerank: bool | None = None,
) -> list[dict]:
    """Recherche dans la base de connaissances.

    `mode="vector"` conserve le comportement du J1 : il n'est pas là par nostalgie mais parce que
    le harness d'évaluation en a besoin comme **point de comparaison chiffré**. Sans référence, on
    ne peut pas affirmer que l'hybride apporte quelque chose.
    """
    if mode == "vector":
        results = await vector_search(question, k)
        return _finalise(results, k)

    # Rappel élargi : on récupère plus de candidats que demandé, puisque la fusion puis le
    # reranking vont resserrer. Chercher 5 pour en garder 5 ne laisserait aucune marge de
    # correction aux deux étages suivants.
    pool_size = max(k * settings.retrieval_pool_factor, k)

    dense = await vector_search(question, pool_size)
    sparse = await lexical.index.search(question, pool_size)

    if not dense and not sparse:
        return []
    if not sparse:
        logger.debug("Recherche lexicale vide - retour au vectoriel seul")
        fused = dense
    elif not dense:
        fused = sparse
    else:
        fused = reciprocal_rank_fusion([dense, sparse], k=settings.rrf_k)

    should_rerank = settings.rerank_enabled if rerank is None else rerank
    if should_rerank:
        from app.kb import rerank as reranker

        fused = reranker.rerank(question, fused)

    return _finalise(fused, k)


def _finalise(results: list[dict], k: int) -> list[dict]:
    """Garantit un `similarity` exploitable et une taille bornée.

    Après reranking, l'ordre vient du cross-encodeur : on expose son score normalisé comme
    `similarity` pour que l'interface n'ait pas à connaître le mode utilisé. Sinon on conserve le
    cosinus. Dans les deux cas, l'appelant reçoit toujours le même contrat.
    """
    out = []
    for doc in results[:k]:
        similarity = doc.get("rerank_score")
        if similarity is None:
            similarity = doc.get("similarity", 0.0)
        out.append(
            {
                "id": doc["id"],
                "title": doc.get("title", ""),
                "source": doc.get("source", ""),
                "chunk_index": doc.get("chunk_index", 0),
                "heading": doc.get("heading"),
                "content": doc["content"],
                "similarity": round(float(similarity), 4),
            }
        )
    return out
