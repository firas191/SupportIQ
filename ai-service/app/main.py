import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes import router
from app.core import db
from app.messaging import consumer

# Logging applicatif visible (les logs du consommateur sont en INFO).
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logging.getLogger("aio_pika").setLevel(logging.WARNING)
logging.getLogger("aiormq").setLevel(logging.WARNING)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Cycle de vie : pool PostgreSQL + consommateur RabbitMQ au demarrage, fermeture propre a l'arret.
    await db.connect()
    await consumer.start()
    try:
        yield
    finally:
        await consumer.stop()
        await db.disconnect()


app = FastAPI(title="SupportIQ — AI Service", version="0.1.0", lifespan=lifespan)
app.include_router(router)
