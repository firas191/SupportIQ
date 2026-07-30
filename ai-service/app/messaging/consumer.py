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
from app.pipeline import embeddings, store, triage
from app.schemas import AnalyzeRequest

logger = logging.getLogger(__name__)

EXCHANGE = "supportiq.tickets"
ROUTING_KEY_CREATED = "ticket.created"
ROUTING_KEY_ANALYZED = "ticket.analyzed"   # publie apres analyse (S4-J5) -> temps reel cote Spring
QUEUE_ANALYZE = "tickets.analyze"
DLX = "supportiq.tickets.dlx"
QUEUE_DLQ = "tickets.analyze.dlq"

MAX_ATTEMPTS = 3          # retries de traitement d'un message
CONNECT_ATTEMPTS = 15     # retries de la connexion initiale au broker

_connection: aio_pika.abc.AbstractRobustConnection | None = None
_consume_task: asyncio.Task | None = None  # ref. de la tache de fond (evite le GC prematuré)
_processed_refs: set[str] = set()  # idempotence J3 (memoire) ; base en S3
_exchange: aio_pika.abc.AbstractExchange | None = None  # pour publier ticket.analyzed (S4-J5)


async def _publish_analyzed(payload: dict, result) -> None:
    """Publie `ticket.analyzed` : Spring le consomme et pousse l'evenement en WebSocket (S4-J5).

    Best-effort : si le broker est indisponible, l'analyse reste persistee en base — on ne perd
    que la notification temps reel.
    """
    if _exchange is None:
        return
    try:
        message = {
            "ticketId": payload.get("ticketId"),
            "externalRef": payload.get("externalRef"),
            "category": result.category.value,
            "priority": result.priority.value,
            "sentiment": result.sentiment.value,
            "confidence": result.confidence,
            "modelUsed": result.model_used,
            "escalatedToLlm": result.escalated_to_llm,
        }
        await _exchange.publish(
            aio_pika.Message(
                body=json.dumps(message).encode(),
                content_type="application/json",
                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
            ),
            routing_key=ROUTING_KEY_ANALYZED,
        )
    except Exception as exc:  # noqa: BLE001 - notification non critique
        logger.warning("Publication ticket.analyzed echouee: %s", exc)


async def _analyze(payload: dict) -> None:
    """Triage hybride (S3-J3) : modèle local + routeur de confiance + escalade LLM, puis persistance."""
    subject = payload.get("subject") or ""
    body = payload.get("body") or ""
    text = f"{subject}\n\n{body}".strip() or subject or body or "(vide)"

    req = AnalyzeRequest(
        ticket_id=payload.get("ticketId"),
        text=text,
        language=payload.get("language"),
    )
    result = await triage.analyze(req)
    await store.save_analysis(payload.get("ticketId"), result)
    await embeddings.store_embedding(payload.get("ticketId"), text)  # vecteur pour /similar (S3-J4)
    await _publish_analyzed(payload, result)  # -> Spring -> WebSocket (S4-J5)

    logger.info(
        "Ticket %s analyse: cat=%s prio=%s sent=%s conf=%.2f modele=%s escalade=%s",
        payload.get("externalRef"),
        result.category.value,
        result.priority.value,
        result.sentiment.value,
        result.confidence,
        result.model_used,
        result.escalated_to_llm,
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
            except Exception as exc:
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
    global _connection, _exchange
    _connection = await _connect()
    if _connection is None:
        return

    channel = await _connection.channel()
    await channel.set_qos(prefetch_count=20)

    exchange = await channel.declare_exchange(EXCHANGE, aio_pika.ExchangeType.TOPIC, durable=True)
    _exchange = exchange  # reutilise pour publier ticket.analyzed (S4-J5)
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
    # On garde la reference (sinon la tache peut etre ramassee par le GC — RUF006).
    global _consume_task
    _consume_task = asyncio.create_task(_consume())


async def stop() -> None:
    if _connection is not None:
        await _connection.close()
