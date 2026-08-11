import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.agents import insight_db
from app.api.routes import router
from app.core import db
from app.messaging import consumer

# Logging applicatif visible (les logs du consommateur sont en INFO).
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logging.getLogger("aio_pika").setLevel(logging.WARNING)
logging.getLogger("aiormq").setLevel(logging.WARNING)

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Cycle de vie : pool PostgreSQL + consommateur RabbitMQ au demarrage, fermeture propre a l'arret.
    await db.connect()
    # Second pool, en **lecture seule**, reserve a l'agent Insight (S6-J1). Utilisateur distinct :
    # le SQL genere par un modele ne doit jamais emprunter les droits de l'application.
    await insight_db.connect()
    await consumer.start()
    try:
        yield
    finally:
        await consumer.stop()
        await insight_db.disconnect()
        await db.disconnect()


app = FastAPI(title="SupportIQ — AI Service", version="0.1.0", lifespan=lifespan)
app.include_router(router)


@app.exception_handler(RequestValidationError)
async def log_validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    """Journalise le corps refusé avant de renvoyer le 422 habituel.

    Sans cela, un 422 de validation est un mur : l'appelant reçoit « unprocessable entity » et le
    serveur n'a rien écrit. Le champ fautif et les octets réellement reçus sont pourtant les deux
    seules informations qui permettent de corriger — et quand l'appelant est un autre service, on
    ne peut pas simplement « regarder ce que le client a envoyé ».

    Le corps est tronqué et journalisé en `repr` : c'est le seul moyen de voir un octet mal encodé,
    qu'un affichage normal masquerait en le remplaçant silencieusement.
    """
    body = await request.body()
    logger.warning(
        "Corps refuse sur %s — erreurs=%s — octets=%r",
        request.url.path,
        exc.errors(),
        body[:400],
    )
    return JSONResponse(status_code=422, content={"detail": exc.errors()})
