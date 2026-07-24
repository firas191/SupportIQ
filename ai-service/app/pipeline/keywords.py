"""Extraction de mots-clés par KeyBERT (S3-J4).

Réutilise le **même modèle d'embeddings** que la similarité (un seul modèle chargé en mémoire).
KeyBERT classe les n-grammes du texte par proximité sémantique avec le document entier.
Résilient : si KeyBERT ou l'embedder manquent, renvoie `[]` (le champ keywords reste vide).
"""
import logging

from app.pipeline import embeddings

logger = logging.getLogger(__name__)

_kb = None
_load_failed = False


def _load() -> None:
    global _kb, _load_failed
    if _kb is not None or _load_failed:
        return
    try:
        from keybert import KeyBERT

        model = embeddings.get_model()
        if model is None:
            raise RuntimeError("modele d'embeddings indisponible")
        _kb = KeyBERT(model=model)
        logger.info("KeyBERT initialise (reutilise l'embedder)")
    except Exception as exc:  # noqa: BLE001
        _load_failed = True
        logger.warning("KeyBERT indisponible (%s) - mots-cles vides", exc)


def extract(text: str, top_n: int = 5) -> list[str]:
    _load()
    if _kb is None or not text:
        return []
    try:
        pairs = _kb.extract_keywords(text, keyphrase_ngram_range=(1, 2), stop_words=None, top_n=top_n)
        return [kw for kw, _score in pairs]
    except Exception as exc:  # noqa: BLE001
        logger.warning("Extraction de mots-cles echouee: %s", exc)
        return []
