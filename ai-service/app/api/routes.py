from fastapi import APIRouter, File, HTTPException, Response, UploadFile, status

from app.core import db
from app.schemas import (
    AnalysisResult,
    AnalyzeRequest,
    DraftResponse,
    InsightRequest,
    InsightResponse,
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
    """Readiness : la base est joignable. 503 si down (utile pour l'orchestration).

    Rapporte aussi l'etat des dependances **optionnelles** — pour l'instant l'acces en lecture seule
    de l'agent Insight (S6-J1). Elles ne conditionnent pas la readiness : le service reste utile
    sans elles. Mais leur etat doit etre lisible sans ouvrir les journaux, sinon diagnostiquer
    « pourquoi Insight ne repond pas » demande un acces au serveur.
    """
    from app.agents import insight_db

    db_up = await db.ping()
    if not db_up:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return {
        "status": "ready" if db_up else "unavailable",
        "database": "up" if db_up else "down",
        "insight_readonly": "up" if insight_db.available() else "down",
    }


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


@router.post("/agents/insight", response_model=InsightResponse)
async def insight(req: InsightRequest) -> InsightResponse:
    """Question d'un manager -> SQL controle -> resultat (rapport §6, S6-J1).

    Le SQL genere traverse deux barrieres independantes avant d'atteindre la base : validation
    syntaxique (sqlglot, SELECT uniquement sur des vues whitelistees) puis execution sous le role
    PostgreSQL `insight_ro`, qui n'a ni droit d'ecriture ni acces aux tables brutes.

    La reponse en langage naturel et la specification de graphique arrivent au S6-J2.
    """
    try:
        from app.agents import insight as agent
    except ImportError as exc:
        # sqlglot absent de l'image : sans analyseur syntaxique, la premiere barriere n'existe pas.
        # On refuse le service plutot que d'executer du SQL non valide — et surtout on le **dit**,
        # au lieu de laisser remonter une 500 dans laquelle rien n'est diagnosticable.
        # Meme traitement que pour l'agent de resolution quand LangGraph manque.
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "Agent Insight indisponible (analyseur SQL absent)",
        ) from exc

    try:
        result = await agent.answer(req.question)
    except agent.InsightError as exc:
        # 422 pour une question hors perimetre ou une requete refusee : la demande est recevable
        # dans sa forme mais ne peut pas aboutir. 503 quand c'est le service qui manque.
        code = (
            status.HTTP_503_SERVICE_UNAVAILABLE
            if exc.code in {"unavailable", "llm_unavailable"}
            else status.HTTP_422_UNPROCESSABLE_ENTITY
        )
        raise HTTPException(code, exc.message) from exc
    return InsightResponse(**result)
