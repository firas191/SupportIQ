"""Scoring du risque de dépassement SLA : modèle entraîné, ou règle de repli (S7-J3).

Même architecture que le triage (S3-J3) : **chargement paresseux et résilient**, repli déterministe
si l'artefact est absent. Le service démarre et sert des scores sans modèle — la file de travail
reste triable le jour où le modèle n'a pas été déployé, ce qui est le cas par défaut.

---

**Calibration exportée en table, pas en objet sérialisé.**

Un `CalibratedClassifierCV` de scikit-learn s'enregistre par `pickle`, ce qui couple l'artefact à la
version exacte de scikit-learn, de numpy et de Python qui l'a produit — et fait échouer le
chargement des mois plus tard, à l'exécution, sur une machine de production. La régression isotone
apprise est donc exportée en **liste de points `(x, y)`**, et appliquée ici par interpolation
linéaire avec la bibliothèque standard. L'artefact est lisible, diffable, et ne dépend d'aucune
version.

**Pourquoi calibrer.** LightGBM optimise une log-loss, ce qui donne des scores ordonnés mais pas des
probabilités : le modèle peut classer parfaitement (AUC élevée) et annoncer 0,8 sur des tickets qui
dépassent une fois sur deux. Or ce chiffre est **affiché à un responsable** qui décide d'agir. Un
score mal calibré est une AUC honnête et une interface qui ment.
"""
from __future__ import annotations

import bisect
import json
import logging
from pathlib import Path

from app.config import settings
from app.sla import features

logger = logging.getLogger(__name__)

MODEL_FILE = "sla_risk.txt"
CALIBRATION_FILE = "sla_calibration.json"

_booster = None
_calibration: list[tuple[float, float]] | None = None
_load_failed = False


def _load() -> None:
    global _booster, _calibration, _load_failed
    if _booster is not None or _load_failed:
        return

    directory = Path(settings.model_dir)
    model_path = directory / MODEL_FILE
    if not model_path.exists():
        _load_failed = True
        logger.info("Modele de risque SLA absent (%s) - repli sur la regle", model_path)
        return

    try:
        import lightgbm as lgb

        _booster = lgb.Booster(model_file=str(model_path))
        _calibration = _read_calibration(directory / CALIBRATION_FILE)
        logger.info(
            "Modele de risque SLA charge (%d arbres, calibration %s)",
            _booster.num_trees(), "chargee" if _calibration else "absente",
        )
    except Exception as exc:  # noqa: BLE001 - lightgbm absent, artefact corrompu
        _load_failed = True
        logger.warning("Modele de risque SLA indisponible (%s) - repli sur la regle", exc)


def _read_calibration(path: Path) -> list[tuple[float, float]] | None:
    if not path.exists():
        # Le modele sans calibration reste utilisable pour **ordonner** la file, ce qui est
        # l'essentiel du service rendu. C'est le chiffre affiche qui perd son sens, pas le tri.
        return None
    try:
        points = json.loads(path.read_text(encoding="utf-8"))
        return sorted((float(x), float(y)) for x, y in points)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Calibration SLA illisible (%s) - scores bruts utilises", exc)
        return None


def available() -> bool:
    _load()
    return _booster is not None


def score(vector: list[float], priority: str | None) -> tuple[float, str]:
    """Probabilité de dépassement et provenance du chiffre (`lightgbm` ou `rules`)."""
    _load()
    if _booster is None:
        return rule_score(vector, priority), "rules"

    try:
        raw = float(_booster.predict([vector])[0])
    except Exception as exc:  # noqa: BLE001 - vecteur mal forme, artefact incompatible
        logger.warning("Prediction SLA echouee (%s) - repli sur la regle", exc)
        return rule_score(vector, priority), "rules"

    return _calibrate(raw), "lightgbm"


def rule_score(vector: list[float], priority: str | None) -> float:
    """Repli : la part du budget SLA déjà consommée.

    C'est aussi la **baseline** que le modèle doit battre pour mériter d'être déployé (ADR-0010).
    Elle n'est pas naïve : c'est exactement ce qu'un responsable calcule de tête, elle est monotone,
    interprétable, et elle a raison sur le cas dominant — un ticket dont l'échéance est passée
    dépasse effectivement son SLA.

    Ce qu'elle ignore, et sur quoi le modèle a une chance de gagner : la charge de la file, l'heure
    d'arrivée, la catégorie. Un ticket à 50 % de son budget un mardi matin dans une file vide n'a
    pas le même destin que le même ticket un vendredi soir derrière quarante autres.
    """
    return max(0.0, min(1.0, features.consumed_fraction(vector, priority)))


def _calibrate(raw: float) -> float:
    """Interpolation linéaire sur la table isotone. Sans table, le score brut est renvoyé tel quel."""
    if not _calibration:
        return max(0.0, min(1.0, raw))

    xs = [x for x, _ in _calibration]
    position = bisect.bisect_left(xs, raw)
    if position == 0:
        return _calibration[0][1]
    if position >= len(_calibration):
        return _calibration[-1][1]

    x0, y0 = _calibration[position - 1]
    x1, y1 = _calibration[position]
    if x1 == x0:
        return y1
    return max(0.0, min(1.0, y0 + (y1 - y0) * (raw - x0) / (x1 - x0)))
