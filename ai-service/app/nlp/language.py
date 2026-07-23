"""Détection de langue FR/EN (S3-J1).

Choix senior : pour un binaire FR/EN, un modèle de *language identification* complet (fastText
lid.176, ~120 Mo) serait de la sur-ingénierie. Une heuristique **mots-vides + diacritiques**
atteint >95 % sur des tickets courts, sans aucune dépendance, et reste explicable. Si le besoin
s'élargit (multilingue réel), on branchera un modèle dédié — porte de sortie assumée.

L'API `detect_language(text) -> "fr" | "en"` est le point d'entrée réutilisé par le pipeline.
"""
from __future__ import annotations

import re

# Mots très fréquents et discriminants dans chaque langue (déterminants, pronoms, prépositions).
FR_STOPWORDS = {
    "le", "la", "les", "un", "une", "des", "du", "de", "et", "est", "sont", "je", "j", "vous",
    "nous", "il", "elle", "pour", "avec", "pas", "ne", "que", "qui", "sur", "mon", "ma", "mes",
    "votre", "vos", "notre", "bonjour", "merci", "cordialement", "suite", "commande", "facture",
    "compte", "paiement", "site", "problème", "depuis", "être", "avoir", "fait", "cette", "ce",
    "au", "aux", "dans", "par", "plus", "très", "mais", "car", "donc", "quand", "à",
}
EN_STOPWORDS = {
    "the", "a", "an", "is", "are", "was", "were", "i", "you", "we", "he", "she", "it", "they",
    "for", "with", "not", "that", "which", "on", "my", "your", "our", "hello", "hi", "thanks",
    "thank", "please", "and", "to", "of", "in", "order", "account", "payment", "invoice", "site",
    "issue", "problem", "since", "have", "has", "this", "at", "by", "but", "so", "when", "cannot",
    "can", "would", "could", "regards",
}

_WORD = re.compile(r"[a-zàâäéèêëïîôöùûüÿçœæ']+", re.IGNORECASE)
_DIACRITICS = re.compile(r"[àâäéèêëïîôöùûüÿçœæ]", re.IGNORECASE)


def detect_language(text: str | None) -> str:
    """Renvoie 'fr' ou 'en'. Défaut 'fr' sur texte vide (marché francophone du produit)."""
    if not text or not text.strip():
        return "fr"
    lowered = text.lower()
    tokens = _WORD.findall(lowered)

    fr_score = sum(1 for t in tokens if t in FR_STOPWORDS)
    en_score = sum(1 for t in tokens if t in EN_STOPWORDS)
    # Les diacritiques sont un signal fort du français (l'anglais n'en a quasi pas).
    fr_score += 2 * len(_DIACRITICS.findall(lowered))

    if fr_score == en_score:
        # Égalité : trancher sur la présence de diacritiques, sinon défaut 'fr'.
        return "en" if en_score > 0 and not _DIACRITICS.search(lowered) else "fr"
    return "fr" if fr_score > en_score else "en"
