"""Recherche lexicale BM25 sur la base de connaissances (S5-J2).

**Pourquoi ajouter du lexical alors qu'on a déjà des vecteurs.** Les deux méthodes échouent sur des
cas opposés, et c'est exactement ce qui les rend complémentaires :

- Le **vecteur** capte le sens : « je n'arrive plus à payer » retrouve la section « Paiement
  refusé » sans partager un seul mot avec elle. Mais il est myope sur les termes rares — un code
  d'erreur, un numéro de version, un nom propre se diluent dans un vecteur de 768 dimensions qui
  résume tout le fragment.
- **BM25** fait l'inverse : il ne comprend rien, mais il trouve `3-D Secure` ou `erreur 500` au mot
  près, et pondère d'autant plus fort que le terme est rare dans le corpus (l'IDF).

Un support client reçoit les deux sortes de questions. D'où la fusion du J2.

**Implémentation en mémoire, assumée.** `rank_bm25` ne persiste rien : on reconstruit l'index depuis
`kb_documents` et on le garde en cache jusqu'à la prochaine écriture. C'est le bon compromis à
l'échelle d'une FAQ (quelques centaines de fragments, indexation en millisecondes). Au-delà de
~50 000 fragments, il faudrait passer à l'index GIN de PostgreSQL — qui, lui, est déjà en place pour
les tickets depuis S4-J3. La porte de sortie est donc connue et documentée.
"""
from __future__ import annotations

import logging
import re
import unicodedata

from app.kb import store

logger = logging.getLogger(__name__)

_TOKEN = re.compile(r"[a-z0-9]+")

# Mots vides FR + EN. Une liste courte volontairement : BM25 pénalise déjà les termes fréquents via
# l'IDF, la liste ne sert qu'à éviter que « comment obtenir un remboursement » ne soit dominé par
# « comment » et « un ». Une liste exhaustive retirerait des mots utiles (« pas », « no ») qui
# portent la négation.
_STOPWORDS = {
    # français
    "le", "la", "les", "un", "une", "des", "du", "de", "et", "ou", "a", "au", "aux", "en", "dans",
    "pour", "par", "sur", "avec", "sans", "que", "qui", "quoi", "dont", "ce", "cet", "cette", "ces",
    "je", "tu", "il", "elle", "nous", "vous", "ils", "elles", "mon", "ma", "mes", "son", "sa",
    "ses", "est", "sont", "etre", "avoir", "ai", "as", "ont", "y", "se", "sa", "si", "comment",
    "quand", "puis", "plus", "moins", "tres", "mais", "donc",
    # anglais
    "the", "a", "an", "of", "and", "or", "to", "in", "on", "for", "with", "without", "that",
    "which", "this", "these", "those", "is", "are", "be", "been", "have", "has", "had", "i", "you",
    "he", "she", "we", "they", "my", "your", "how", "when", "what", "can", "do", "does", "did",
}


def tokenize(text: str) -> list[str]:
    """Découpe en termes comparables.

    Les accents sont retirés : un client qui écrit « delai » doit trouver « délai ». C'est la même
    logique que la configuration `unaccent` qu'on n'a pas pu appliquer à la recherche full-text des
    tickets en S4-J3 (une colonne générée exige une expression IMMUTABLE) — ici, rien ne l'empêche.
    """
    normalised = unicodedata.normalize("NFD", text.lower())
    stripped = "".join(c for c in normalised if unicodedata.category(c) != "Mn")
    return [t for t in _TOKEN.findall(stripped) if t not in _STOPWORDS and len(t) > 1]


class LexicalIndex:
    """Index BM25 du corpus, reconstruit à la demande."""

    def __init__(self) -> None:
        self._bm25 = None
        self._chunks: list[dict] = []
        self._stale = True

    def invalidate(self) -> None:
        """Marque l'index périmé. Appelé après toute écriture dans la base de connaissances."""
        self._stale = True

    async def ensure(self) -> None:
        if not self._stale and self._bm25 is not None:
            return

        chunks = await store.all_chunks(with_meta=True)
        if not chunks:
            self._bm25, self._chunks, self._stale = None, [], False
            return

        try:
            from rank_bm25 import BM25Okapi
        except ImportError:
            logger.warning("rank_bm25 absent - recherche lexicale desactivee")
            self._bm25, self._chunks, self._stale = None, [], False
            return

        # Le titre de section est indexé avec le fragment, exactement comme pour l'embedding :
        # les deux voies doivent voir le même texte, sinon leurs classements ne sont pas comparables.
        corpus = [
            tokenize(f"{c['heading']} {c['content']}" if c["heading"] else c["content"])
            for c in chunks
        ]
        self._bm25 = BM25Okapi(corpus)
        self._chunks = chunks
        self._stale = False
        logger.info("Index lexical reconstruit: %d fragments", len(chunks))

    async def search(self, question: str, k: int) -> list[dict]:
        """Top-k fragments par score BM25. Liste vide si l'index n'est pas disponible."""
        await self.ensure()
        if self._bm25 is None or not self._chunks:
            return []

        terms = tokenize(question)
        if not terms:
            return []

        scores = self._bm25.get_scores(terms)
        ranked = sorted(range(len(scores)), key=lambda i: scores[i], reverse=True)[:k]

        # Un score nul signifie « aucun terme en commun » : remonter ces fragments polluerait la
        # fusion avec des candidats que le lexical n'a en réalité pas trouvés.
        return [
            {**self._chunks[i], "score": float(scores[i])}
            for i in ranked
            if scores[i] > 0
        ]


index = LexicalIndex()
