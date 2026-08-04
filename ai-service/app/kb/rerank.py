"""Reranking par cross-encodeur (S5-J2).

**Bi-encodeur contre cross-encodeur — la distinction qui explique tout le J2.**

Le bi-encodeur (e5) encode la question et le fragment *indépendamment*, puis compare deux vecteurs.
C'est ce qui le rend utilisable à l'échelle : les vecteurs des fragments sont calculés une fois pour
toutes à l'indexation, et la recherche n'est qu'un produit scalaire. Mais il paie ce gain d'un
handicap structurel : au moment d'encoder le fragment, **il ne sait pas quelle sera la question**.
Il doit produire un résumé universel, bon en moyenne, précis nulle part.

Le cross-encodeur lit la paire `(question, fragment)` **ensemble**, dans un seul passage
d'attention. Chaque mot de la question peut « regarder » chaque mot du fragment. Le score qui en
sort est nettement plus discriminant — c'est ce qui règle la compression observée au J1, où les cinq
premiers résultats se tenaient dans 6 points.

Le prix : il n'y a rien à précalculer, il faut un passage de modèle **par candidat**. Impossible sur
tout le corpus, parfait sur vingt candidats déjà présélectionnés. D'où l'architecture en deux
étages : rappel large et bon marché, puis précision chère et courte.

Chargement paresseux et résilient, comme partout ailleurs : sans le modèle, `rerank()` rend la liste
inchangée et la recherche reste celle du RRF. On ne casse jamais la fonctionnalité pour un modèle
absent.
"""
from __future__ import annotations

import logging
import math

from app.config import settings

logger = logging.getLogger(__name__)

_model = None
_load_failed = False


def _load():
    global _model, _load_failed
    if _model is not None or _load_failed:
        return _model
    try:
        from sentence_transformers import CrossEncoder

        _model = CrossEncoder(settings.rerank_model, max_length=512)
        logger.info("Modele de reranking charge: %s", settings.rerank_model)
    except Exception as exc:  # noqa: BLE001
        _load_failed = True
        logger.warning("Reranking indisponible (%s) - classement RRF conserve", exc)
    return _model


def rerank(question: str, candidates: list[dict]) -> list[dict]:
    """Reclasse les candidats. Renvoie la liste inchangée si le modèle est absent."""
    model = _load()
    if model is None or len(candidates) < 2:
        return candidates

    # Le titre de section accompagne le fragment, comme à l'indexation : le cross-encodeur doit
    # voir le même texte que celui qui a été embeddé, sinon on compare deux représentations
    # différentes du même document.
    pairs = [
        [question, f"{c['heading']}\n{c['content']}" if c.get("heading") else c["content"]]
        for c in candidates
    ]

    try:
        scores = model.predict(pairs)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Reranking echoue (%s) - classement RRF conserve", exc)
        return candidates

    scored = [
        {**candidate, "rerank_score": _to_unit(float(score))}
        for candidate, score in zip(candidates, scores, strict=False)
    ]
    scored.sort(key=lambda c: c["rerank_score"], reverse=True)
    return scored


def _to_unit(logit: float) -> float:
    """Ramène le score du cross-encodeur dans [0, 1].

    Un cross-encodeur sort un **logit**, non borné (typiquement de -11 à +11). L'interface, elle,
    affiche un pourcentage : sans transformation, un score de 8,3 n'aurait aucun sens à l'écran.
    La sigmoïde est la fonction inverse de celle utilisée à l'entraînement — elle rend donc la
    probabilité de pertinence que le modèle a réellement apprise, pas une remise à l'échelle
    arbitraire.
    """
    if logit >= 0:
        return 1.0 / (1.0 + math.exp(-logit))
    # Forme numériquement stable pour les logits très négatifs (exp(+700) déborde).
    z = math.exp(logit)
    return z / (1.0 + z)
