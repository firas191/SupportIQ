from fastapi import APIRouter, File, HTTPException, Response, UploadFile, status

from app.core import db
from app.schemas import (
    AnalysisResult,
    AnalyzeRequest,
    DraftResponse,
    KbChunk,
    KbDocument,
    KbIngestResult,
    KbReindexResult,
    KbSearchRequest,
    ResolutionRequest,
    SimilarRequest,
    SimilarTicket,
)

router = APIRouter()


@router.get("/health")
async def health() -> dict:
    """Liveness : le process repond (independant de la base)."""
    return {"status": "ok", "service": "supportiq-ai"}


@router.get("/health/ready")
async def ready(response: Response) -> dict:
    """Readiness : la base est joignable. 503 si down (utile pour l'orchestration)."""
    db_up = await db.ping()
    if not db_up:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return {"status": "ready" if db_up else "unavailable", "database": "up" if db_up else "down"}


@router.post("/analyze", response_model=AnalysisResult)
async def analyze(req: AnalyzeRequest) -> AnalysisResult:
    from app.pipeline.triage import analyze as run

    return await run(req)


@router.post("/similar", response_model=list[SimilarTicket])
async def similar(req: SimilarRequest) -> list[SimilarTicket]:
    """Top-k tickets sémantiquement proches (pgvector), avec suggestion de doublon."""
    from app.pipeline import embeddings

    rows = await embeddings.find_similar(req.ticket_id, req.text, req.k)
    return [SimilarTicket(**r) for r in rows]


@router.post("/embeddings/backfill")
async def backfill() -> dict:
    """Embedde les tickets existants sans vecteur (rattrapage/démo)."""
    from app.pipeline import embeddings

    return {"embedded": await embeddings.backfill()}


# ---------------------------------------------------------------------------
# Base de connaissances (S5-J1)
#
# Ces routes ne sont pas exposées au navigateur : le frontend passe toujours par
# Spring, qui porte l'authentification et le RBAC (rapport §6). Elles sont
# appelées par le plan de contrôle, comme /similar depuis S4-J4.
# ---------------------------------------------------------------------------


@router.post("/kb/documents", response_model=KbIngestResult)
async def kb_ingest(file: UploadFile = File(...)) -> KbIngestResult:
    """Indexe un document : lecture, découpage sémantique, embeddings, persistance."""
    from app.kb import service
    from app.kb.loader import UnsupportedDocument

    data = await file.read()
    try:
        result = await service.ingest(file.filename or "document", data)
    except UnsupportedDocument as exc:
        # 415 et non 400 : le fichier est lisible, c'est son **format** qui est refusé.
        raise HTTPException(status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, str(exc)) from exc
    return KbIngestResult(**result)


@router.get("/kb/documents", response_model=list[KbDocument])
async def kb_documents() -> list[KbDocument]:
    from app.kb import service

    return [KbDocument(**d) for d in await service.documents()]


@router.delete("/kb/documents/{source:path}")
async def kb_delete(source: str) -> dict:
    from app.kb import service

    return {"deleted": await service.remove(source)}


@router.post("/kb/reindex", response_model=KbReindexResult)
async def kb_reindex(force: bool = False) -> KbReindexResult:
    """Recalcule les vecteurs manquants ; `force=true` recalcule tout (changement de modèle)."""
    from app.kb import service

    return KbReindexResult(**await service.reindex(force=force))


@router.post("/kb/search", response_model=list[KbChunk])
async def kb_search(req: KbSearchRequest) -> list[KbChunk]:
    """Recherche hybride (S5-J2) : BM25 + vecteurs, fusion RRF, reranking cross-encodeur."""
    from app.kb import service

    return [KbChunk(**c) for c in await service.search(req.question, req.k, req.mode)]


# ---------------------------------------------------------------------------
# Agents (S5-J3)
# ---------------------------------------------------------------------------


@router.post("/agents/resolution", response_model=DraftResponse)
async def resolution(req: ResolutionRequest) -> DraftResponse:
    """Genere un brouillon de reponse **cite** pour un ticket (rapport §6).

    Le brouillon n'est jamais envoye : il est propose a un agent humain qui le valide, le corrige
    ou le rejette (S5-J4). `low_confidence` a vrai signale que l'auto-verification n'a pas converge
    — l'interface doit alors avertir avant meme la lecture.
    """
    from app.agents import resolution as agent

    try:
        result = await agent.run(req.ticket_id, req.tone)
    except ValueError as exc:
        raise HTTPException(status.HTTP_404_NOT_FOUND, str(exc)) from exc
    except ImportError as exc:
        # LangGraph absent : l'agent est indisponible, le reste du service fonctionne.
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "Agent de resolution indisponible"
        ) from exc
    return DraftResponse(**result)
