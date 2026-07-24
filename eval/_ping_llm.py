"""Diagnostic rapide de la passerelle LLM (S3-J5). À lancer dans le conteneur :

    docker compose exec ai-service python /eval/_ping_llm.py
"""
import asyncio
import sys

sys.path.insert(0, "/srv")

from app.core.llm import _groq_keys, complete  # noqa: E402


async def main() -> None:
    print(f"Cles Groq detectees : {len(_groq_keys())}")
    messages = [{"role": "user", "content": "Reponds en JSON: {\"ok\": true}"}]
    try:
        out = await complete(messages, {"type": "json_object"})
        print("OK, reponse LLM :", out[:120])
    except Exception as exc:  # noqa: BLE001
        print("ECHEC :", exc)


if __name__ == "__main__":
    asyncio.run(main())
