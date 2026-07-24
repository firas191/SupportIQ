"""Passerelle LLM unique via LiteLLM — failover multi-clés Groq -> Gemini -> OpenRouter -> Ollama.

Toute la logique d'appel LLM du projet passe par ici : un seul point d'instrumentation (Langfuse),
de retry et de budget de tokens.

Robustesse : on empile **plusieurs clés Groq** (virgules dans `GROQ_API_KEY` et/ou suffixes
`GROQ_API_KEY_2`, `_3`, …) et on bascule au 401/429 — comme le générateur d'eval. Modèle Groq =
`llama-3.1-8b-instant` (budget/jour bien plus large que le 70b, suffisant pour l'escalade d'un
ticket incertain). Puis repli Gemini / OpenRouter / Ollama.
"""
import logging
import os

import litellm

from app.config import settings

logger = logging.getLogger(__name__)
litellm.suppress_debug_info = True  # réduit le spam de litellm quand un provider échoue

GROQ_MODEL = "groq/llama-3.1-8b-instant"
OTHER_PROVIDERS = [
    ("gemini/gemini-2.0-flash", None),
    ("openrouter/meta-llama/llama-3.3-70b-instruct", None),  # slug ':free' retiré par OpenRouter
    ("ollama/qwen2.5:3b", None),
]

# Observabilité Langfuse (S3-J5) : chaque appel LLM est tracé (latence, tokens, coût, prompt).
# Activé UNIQUEMENT si les clés sont configurées (sinon no-op — litellm n'essaie pas d'exporter).
if settings.langfuse_public_key and settings.langfuse_secret_key:
    try:
        litellm.success_callback = ["langfuse"]
        litellm.failure_callback = ["langfuse"]
        logger.info("Langfuse activé sur la passerelle LLM")
    except Exception as exc:  # noqa: BLE001 - observabilité optionnelle, ne doit jamais casser le service
        logger.warning("Langfuse indisponible (%s) - traces désactivées", exc)


def _groq_keys() -> list[str]:
    """Toutes les clés Groq : GROQ_API_KEY (éventuellement liste séparée par des virgules) + GROQ_API_KEY_2, _3, …"""
    keys: list[str] = []
    raw = os.environ.get("GROQ_API_KEY", "")
    keys += [k.strip() for k in raw.split(",") if k.strip()]
    i = 2
    while os.environ.get(f"GROQ_API_KEY_{i}"):
        keys += [k.strip() for k in os.environ[f"GROQ_API_KEY_{i}"].split(",") if k.strip()]
        i += 1
    return keys


async def complete(messages: list[dict], response_format: dict | None = None) -> str:
    # Groq d'abord (une tentative par clé — rotation multi-comptes), puis les autres fournisseurs
    # (litellm lit leur clé dans l'environnement).
    attempts = [(GROQ_MODEL, key) for key in _groq_keys()] + OTHER_PROVIDERS
    last_error: Exception | None = None
    for model, api_key in attempts:
        try:
            kwargs = {
                "model": model,
                "messages": messages,
                "response_format": response_format,
                "max_tokens": 1024,
                "timeout": 30,
            }
            if api_key:
                kwargs["api_key"] = api_key
            resp = await litellm.acompletion(**kwargs)
            return resp.choices[0].message.content
        except Exception as exc:  # noqa: BLE001 - clé invalide, quota, timeout, provider down
            last_error = exc
            continue
    raise RuntimeError(f"Tous les fournisseurs LLM ont échoué: {last_error}")
