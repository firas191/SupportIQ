"""Connexion en moindre privilège de l'agent Insight (S6-J1).

**Un second pool, avec un autre utilisateur.** Le service possède déjà `app.core.db`, connecté en
tant que propriétaire du schéma — il écrit les analyses, les embeddings, les brouillons. Faire
passer le SQL généré par ce pool reviendrait à donner au modèle les droits de l'application, et la
validation AST serait alors la **seule** chose entre une question de manager et un `DROP TABLE`.

Le rôle `insight_ro` (migration V11) n'a le droit de lire que six vues et refuse toute écriture, au
niveau du moteur. C'est la deuxième des deux barrières : la première est du code et peut avoir un
trou, la seconde est appliquée par PostgreSQL.

Formulé autrement : si `sql_guard` disparaissait entièrement, la pire chose qu'un attaquant
obtiendrait serait de lire des agrégats sans donnée personnelle. C'est le test qu'on devrait faire
passer à toute couche de sécurité — *que se passe-t-il si celle du dessus tombe ?*
"""
from __future__ import annotations

import logging

import asyncpg

from app.config import settings
from app.core import db as app_db

logger = logging.getLogger(__name__)

_pool: asyncpg.Pool | None = None


def _dsn() -> str:
    """DSN du propriétaire, avec l'utilisateur et le mot de passe remplacés.

    On dérive de `database_url` plutôt que d'ajouter une variable d'environnement complète : hôte,
    port et base sont forcément les mêmes, et deux URL à maintenir en parallèle finissent toujours
    par diverger d'un caractère un jour de déploiement.
    """
    base = app_db._dsn()
    _, _, tail = base.partition("://")
    _, _, host_part = tail.rpartition("@")
    return f"postgresql://{settings.insight_db_user}:{settings.insight_db_password}@{host_part}"


async def connect() -> None:
    """Ouvre le pool en lecture seule. Un échec n'empêche pas le service de démarrer.

    Même résilience qu'ailleurs : si le rôle n'existe pas encore (migration V11 non appliquée),
    seul l'agent Insight devient indisponible, et il le dit clairement.
    """
    global _pool
    try:
        _pool = await asyncpg.create_pool(
            dsn=_dsn(),
            min_size=1,
            max_size=3,
            timeout=10,
            server_settings={
                # Troisième couche, posée à la connexion : même si le rôle perdait ses réglages,
                # la session reste bornée en temps et en écriture.
                "statement_timeout": str(settings.insight_statement_timeout_ms),
                "default_transaction_read_only": "on",
                # Empêche d'atteindre un objet par un schéma non qualifié inattendu.
                "search_path": "public",
                "application_name": "supportiq-insight",
            },
        )
        logger.info("Pool Insight (lecture seule) initialise")
    except asyncpg.InvalidPasswordError as exc:
        _pool = None
        # PostgreSQL renvoie **le même message** pour un rôle inexistant et pour un mot de passe
        # faux : c'est délibéré de sa part (on ne veut pas qu'un attaquant énumère les comptes).
        # Le message brut est donc trompeur pour l'exploitant. On explicite les deux causes, dans
        # l'ordre de probabilité — la migration non appliquée est de loin la plus fréquente.
        logger.warning(
            "Pool Insight indisponible pour l'utilisateur '%s'. PostgreSQL ne distingue pas "
            "« role inexistant » de « mot de passe errone » : verifier (1) que la migration V11 "
            "est bien appliquee (table flyway_schema_history), (2) que INSIGHT_DB_PASSWORD est "
            "identique cote backend (placeholder Flyway) et cote service IA. Detail: %s",
            settings.insight_db_user,
            exc,
        )
    except Exception as exc:  # noqa: BLE001 - base indisponible, reseau
        _pool = None
        logger.warning("Pool Insight indisponible: %s", exc)


async def disconnect() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None


def available() -> bool:
    return _pool is not None


async def run_query(sql: str) -> tuple[list[str], list[list]]:
    """Exécute une requête **déjà validée** et renvoie (colonnes, lignes).

    La transaction est explicitement `read_only` : un `BEGIN READ ONLY` fait échouer toute écriture
    au niveau du moteur, quelle que soit la façon dont elle est arrivée jusqu'ici.
    """
    return await run_query_args(sql)


async def run_query_args(sql: str, *args) -> tuple[list[str], list[list]]:
    """Variante paramétrée, pour les requêtes **fixes** du digest (S6-J4).

    Les paramètres passent par asyncpg (`$1`, `$2`) et ne sont jamais concaténés : les bornes de
    semaine viennent du code, mais une date interpolée dans du SQL est une habitude qui finit
    toujours par rencontrer une valeur qui ne vient pas du code.

    Ces requêtes ne traversent pas `sql_guard` — elles sont écrites à la main dans le dépôt, pas
    produites par un modèle. La garde protège d'un texte d'origine incontrôlée ; l'appliquer ici
    reviendrait à se méfier de son propre code source, et masquerait la distinction qui compte.
    """
    if _pool is None:
        raise InsightUnavailable("Le service d'analyse n'a pas d'acces en lecture seule a la base")

    async with _pool.acquire() as conn:
        async with conn.transaction(readonly=True):
            records = await conn.fetch(sql, *args)

    if not records:
        return [], []
    columns = list(records[0].keys())
    # Les valeurs sont converties en types Python simples : ce résultat part en JSON, et un
    # `Decimal` ou un `date` d'asyncpg y échouerait silencieusement au moment de la sérialisation.
    return columns, [[_plain(value) for value in record.values()] for record in records]


def _plain(value):
    import datetime
    import decimal
    import uuid

    if isinstance(value, decimal.Decimal):
        return float(value)
    if isinstance(value, (datetime.datetime, datetime.date, datetime.time)):
        return value.isoformat()
    if isinstance(value, uuid.UUID):
        return str(value)
    return value


class InsightUnavailable(RuntimeError):
    """L'accès en lecture seule n'est pas disponible (rôle absent, base injoignable)."""
