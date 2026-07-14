"""Passerelle LLM unique via LiteLLM — failover Groq -> Gemini -> OpenRouter -> Ollama.

Toute la logique d'appel LLM du projet passe par ici : un seul point
d'instrumentation (Langfuse), de retry et de budget de tokens.
"""
import litellm

FALLBACK_CHAIN = [
    "groq/llama-3.3-70b-versatile",
    "gemini/gemini-2.0-flash",
    "openrouter/meta-llama/llama-3.3-70b-instruct:free",
    "ollama/qwen2.5:3b",
]


async def complete(messages: list[dict], response_format: dict | None = None) -> str:
    last_error: Exception | None = None
    for model in FALLBACK_CHAIN:
        try:
            resp = await litellm.acompletion(
                model=model,
                messages=messages,
                response_format=response_format,
                max_tokens=1024,
                timeout=30,
            )
            return resp.choices[0].message.content
        except Exception as exc:  # quota épuisé, timeout, provider down
            last_error = exc
            continue
    raise RuntimeError(f"Tous les fournisseurs LLM ont échoué: {last_error}")
