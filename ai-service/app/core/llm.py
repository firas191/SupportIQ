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
from app.core import circuit, run_context

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


async def complete(
    messages: list[dict],
    response_format: dict | None = None,
    groq_model: str | None = None,
    temperature: float | None = None,
) -> str:
    """Complétion avec bascule automatique entre fournisseurs.

    `groq_model` permet de demander un modèle Groq **différent du modèle courant**. Un seul appelant
    s'en sert aujourd'hui : le juge de brouillons (S5-J5). La raison n'est pas la performance mais
    l'**indépendance de la mesure** — un modèle qui note sa propre production se préfère
    systématiquement (biais d'auto-préférence, bien documenté sur les protocoles LLM-as-judge). Le
    même principe avait été appliqué au filtre d'accord du jeu de données en S2-J5.

    La chaîne de repli reste identique : si le modèle demandé est indisponible, on retombe sur le
    modèle standard puis sur les autres fournisseurs. Une mesure dégradée reste préférable à
    l'absence de mesure — à condition de le savoir, d'où la remontée du modèle réellement utilisé
    par `complete_with_model`.

    `temperature=0` demande la sortie la plus probable plutôt qu'un tirage. À utiliser partout où le
    résultat est **vérifiable** — traduction en SQL, extraction, notation — et à laisser libre là où
    la variation est un service rendu (rédaction d'un brouillon client). Mesuré en S6-J2 : sans
    température fixée, deux exécutions de la même suite de 30 questions changeaient de verdict sur
    **11 questions**. La suite mesurait alors autant le hasard que la capacité.
    """
    text, _ = await complete_with_model(messages, response_format, groq_model, temperature)
    return text


async def complete_with_model(
    messages: list[dict],
    response_format: dict | None = None,
    groq_model: str | None = None,
    temperature: float | None = None,
) -> tuple[str, str]:
    """Comme `complete`, mais renvoie aussi le modèle qui a effectivement répondu.

    Utile partout où le résultat sert de **mesure** : un chiffre obtenu avec un modèle de repli ne
    se compare pas à un chiffre obtenu avec le modèle prévu, et on ne peut pas s'en apercevoir après
    coup si l'information est jetée.
    """
    # Le budget est vérifié **avant** d'émettre quoi que ce soit. Contrôler après l'appel serait
    # une comptabilité, pas une limite (S6-J5).
    run = run_context.current()
    if run is not None:
        run.check_budget()

    # Groq d'abord (une tentative par clé — rotation multi-comptes), puis les autres fournisseurs
    # (litellm lit leur clé dans l'environnement).
    keys = _groq_keys()
    attempts: list[tuple[str, str | None]] = []
    if groq_model and groq_model != GROQ_MODEL:
        attempts += [(groq_model, key) for key in keys]
    attempts += [(GROQ_MODEL, key) for key in keys] + OTHER_PROVIDERS

    last_error: Exception | None = None
    for index, (model, api_key) in enumerate(attempts):
        # Un fournisseur dont le circuit est ouvert est **sauté sans appel**. C'est tout l'intérêt :
        # quand le quota Groq est épuisé, on ne repaie pas trois délais d'expiration par requête.
        if circuit.is_open(_circuit_key(model, api_key)):
            continue

        try:
            kwargs = {
                "model": model,
                "messages": messages,
                "response_format": response_format,
                "max_tokens": 1024,
                "timeout": 30,
            }
            if temperature is not None:
                kwargs["temperature"] = temperature
            if api_key:
                kwargs["api_key"] = api_key
            resp = await litellm.acompletion(**kwargs)

        except Exception as exc:  # noqa: BLE001 - clé invalide, quota, timeout, provider down
            circuit.record_failure(_circuit_key(model, api_key), exc)
            last_error = exc
            continue

        circuit.record_success(_circuit_key(model, api_key))
        if run is not None:
            prompt, completion = _usage(resp)
            # `index > 0` = on n'a pas obtenu le fournisseur prévu. Le run est marqué dégradé, ce
            # qui permet plus tard de ne pas comparer sa qualité à celle d'un run nominal.
            run.record(model, prompt, completion, degraded=index > 0)
        return resp.choices[0].message.content, model

    raise RuntimeError(f"Tous les fournisseurs LLM ont échoué: {last_error}")


def _circuit_key(model: str, api_key: str | None) -> str:
    """Un circuit **par clé** et non par fournisseur.

    Le multi-comptes Groq (S2-J5) existe précisément parce que les quotas sont par compte : une clé
    épuisée n'empêche pas la suivante de répondre. Les regrouper sous un seul circuit annulerait le
    bénéfice du dispositif au premier compte à court.
    """
    if api_key is None:
        return model
    return f"{model}#{api_key[-6:]}"


def _usage(response) -> tuple[int, int]:
    """Jetons consommés. Renvoie (0, 0) si le fournisseur ne les rapporte pas.

    Tous ne le font pas — Ollama en local, notamment. Un décompte partiel reste utile ; refuser de
    compter faute d'exhaustivité ne rendrait service à personne.
    """
    usage = getattr(response, "usage", None)
    if usage is None:
        return 0, 0
    return int(getattr(usage, "prompt_tokens", 0) or 0), int(
        getattr(usage, "completion_tokens", 0) or 0
    )


# Modèle réservé au jugement : nettement plus grand que le rédacteur (8b), et déjà utilisé comme
# arbitre pour le filtre d'accord du jeu de données (S2-J5). Le volume est faible — quelques
# dizaines d'appels par campagne d'évaluation — donc son coût par jeton n'est pas dimensionnant.
JUDGE_MODEL = "groq/llama-3.3-70b-versatile"
