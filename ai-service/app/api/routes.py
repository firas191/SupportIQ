from fastapi import APIRouter, Response, status

from app.core import db
from app.schemas import AnalysisResult, AnalyzeRequest, SimilarRequest, SimilarTicket

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
