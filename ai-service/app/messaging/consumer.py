"""Consommateur RabbitMQ (S2-J3).

Ecoute la queue `tickets.analyze` alimentee par Spring (`ticket.created`) et, pour chaque
message, declenchera le triage IA (Semaine 3). Au J3, on logue simplement le ticket recu pour
prouver la traversee Spring -> RabbitMQ -> FastAPI.

Garanties :
- topologie declaree a l'identique de Spring (declaration idempotente) ;
- reconnexion : on reessaie la connexion initiale (le broker peut demarrer apres le service) ;
- acquittement seulement apres traitement reussi ; en cas d'echec definitif -> dead-letter queue ;
- idempotence par external_ref (ensemble en memoire au J3 ; a persister en base en S3).
"""
import asyncio
import json
import logging

import aio_pika

from app.config import settings

logger = logging.getLogger(__name__)

EXCHANGE = "supportiq.tickets"
ROUTING_KEY_CREATED = "ticket.created"
QUEUE_ANALYZE = "tickets.analyze"
DLX = "supportiq.tickets.dlx"
QUEUE_DLQ = "tickets.analyze.dlq"

MAX_ATTEMPTS = 3          # retries de traitement d'un message
CONNECT_ATTEMPTS = 15     # retries de la connexion initiale au broker

_connection: aio_pika.abc.AbstractRobustConnection | None = None
_processed_refs: set[str] = set()  # idempotence J3 (memoire) ; base en S3


async def _analyze(payload: dict) -> None:
    """Placeholder du triage (implemente en S3). Au J3 : trace la reception."""
    logger.info(
        "Ticket recu: id=%s ref=%s lang=%s subject=%r",
        payload.get("ticketId"),
        payload.get("externalRef"),
        payload.get("language"),
        payload.get("subject"),
    )


async def _handle(message: aio_pika.abc.AbstractIncomingMessage) -> None:
    # requeue=False : en cas d'exception non geree, le message part en dead-letter queue.
    async with message.process(requeue=False):
        payload = json.loads(message.body.decode())
        ref = payload.get("externalRef")
        if ref and ref in _processed_refs:
            logger.info("Ticket %s deja traite (idempotence), ignore", ref)
            return

        for attempt in range(1, MAX_ATTEMPTS + 1):
            try:
                await _analyze(payload)
                break
            except Exception as exc:  # noqa: BLE001
                if attempt == MAX_ATTEMPTS:
                    logger.error("Echec definitif du ticket %s -> DLQ: %s", ref, exc)
                    raise
                delay = 2 ** (attempt - 1)
                logger.warning("Echec ticket %s (essai %d/%d), retry dans %ds",
                               ref, attempt, MAX_ATTEMPTS, delay)
                await asyncio.sleep(delay)

        if ref:
            _processed_refs.add(ref)


async def _connect() -> aio_pika.abc.AbstractRobustConnection | None:
    for attempt in range(1, CONNECT_ATTEMPTS + 1):
        try:
            return await aio_pika.connect_robust(settings.rabbitmq_url)
        except Exception as exc:  # noqa: BLE001
            if attempt == CONNECT_ATTEMPTS:
                logger.warning("RabbitMQ injoignable apres %d tentatives: %s", CONNECT_ATTEMPTS, exc)
                return None
            delay = min(2 ** attempt, 15)
            logger.info("RabbitMQ pas encore pret (essai %d/%d), retry dans %ds",
                        attempt, CONNECT_ATTEMPTS, delay)
            await asyncio.sleep(delay)
    return None


async def _consume() -> None:
    global _connection
    _connection = await _connect()
    if _connection is None:
        return

    channel = await _connection.channel()
    await channel.set_qos(prefetch_count=20)

    exchange = await channel.declare_exchange(EXCHANGE, aio_pika.ExchangeType.TOPIC, durable=True)
    await channel.declare_exchange(DLX, aio_pika.ExchangeType.TOPIC, durable=True)

    dlq = await channel.declare_queue(QUEUE_DLQ, durable=True)
    await dlq.bind(DLX, ROUTING_KEY_CREATED)

    queue = await channel.declare_queue(
        QUEUE_ANALYZE,
        durable=True,
        arguments={
            "x-dead-letter-exchange": DLX,
            "x-dead-letter-routing-key": ROUTING_KEY_CREATED,
        },
    )
    await queue.bind(exchange, ROUTING_KEY_CREATED)

    logger.info("Consommateur RabbitMQ demarre (queue '%s')", QUEUE_ANALYZE)
    await queue.consume(_handle)


async def start() -> None:
    # Tache de fond : ne bloque pas le demarrage de FastAPI (resilient si le broker est down).
    asyncio.create_task(_consume())


async def stop() -> None:
    if _connection is not None:
        await _connection.close()
