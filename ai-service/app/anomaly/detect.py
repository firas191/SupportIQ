"""Détection d'anomalies de volume : décomposition saisonnière + score robuste (S7-J2, rapport §9).

```
série horaire ──décomposition──> résidu ──score robuste (MAD)──> anomalie ?
```

Module **pur** : aucune base, aucun réseau, aucun modèle. C'est ce qui permet de le tester sur des
séries construites à la main, où l'on connaît la réponse.

---

**Pourquoi décomposer avant de juger.** Le volume de tickets a un rythme : les nuits sont vides, les
matinées chargées. Un score calculé directement sur les comptes déclencherait tous les jours à 9 h.
Une alerte qui se déclenche à heure fixe n'apprend rien à personne, et la première chose qu'un
responsable fait avec, c'est la désactiver. La question posée n'est donc pas « y a-t-il beaucoup de
tickets ? » mais **« y en a-t-il plus que d'habitude à cette heure-ci ? »**.

**Pourquoi le MAD et pas l'écart-type.** C'est la décision centrale de la journée. L'anomalie qu'on
cherche est *dans l'échantillon* qui sert à calculer la normale. Une seule heure à dix fois la
normale gonfle assez l'écart-type pour que le pic retombe sous les trois sigmas — le détecteur est
aveuglé par ce qu'il devrait voir. L'écart absolu médian (MAD) ne bouge pas pour un point : il faut
en corrompre la moitié pour le déplacer. C'est le **point de rupture** de l'estimateur, 50 % contre
0 %.

Le score employé est le *modified z-score* d'Iglewicz & Hoaglin : `0.6745 · (x - médiane) / MAD`.
(Trait d'union ordinaire et non le signe moins U+2212 : le raffinement typographique a déjà été
abandonné au S6-J4 pour la même raison, et je viens de le réintroduire par distraction.)
Le facteur 0,6745 ramène le MAD à l'échelle d'un écart-type sous une loi normale, ce qui permet de
garder l'intuition « au-delà de 3,5, c'est anormal » — seuil recommandé par les mêmes auteurs.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime
from statistics import median

logger = logging.getLogger(__name__)

#: Période saisonnière : 24 heures.
SEASON = 24

#: Historique minimal pour décomposer. En dessous de deux périodes complètes, la forme
#: saisonnière n'est pas estimable et STL refuse de travailler — à juste titre.
MIN_HISTORY = 2 * SEASON

#: Facteur de cohérence du MAD avec l'écart-type sous une loi normale (Iglewicz & Hoaglin).
MAD_SCALE = 0.6745

#: Seuils du score robuste. 3,5 est la valeur recommandée par Iglewicz & Hoaglin ; 6 est un choix
#: du projet pour séparer « à regarder » de « à traiter maintenant ».
WARNING_SCORE = 3.5
CRITICAL_SCORE = 6.0

#: Dispersion minimale exploitable, en tickets.
#:
#: Les comptes sont des **entiers**. Un écart absolu médian inférieur à un demi-ticket ne mesure
#: aucune variabilité réelle : c'est soit une série constante, soit — sur une série parfaitement
#: régulière — le bruit de calcul flottant des résidus. Diviser par une telle valeur fabrique de la
#: certitude à partir de rien, et produit des scores de plusieurs milliers sur des séries où il ne
#: s'est rien passé.
MIN_DEVIATION = 0.5

#: Plancher absolu. Une anomalie sur 3 tickets n'est pas une anomalie.
#:
#: Sans ce plancher, une catégorie qui reçoit un ticket toutes les deux semaines déclencherait une
#: alerte CRITICAL le jour où elle en reçoit deux : le MAD d'une série presque toujours nulle vaut
#: zéro, et tout écart devient infiniment significatif. C'est la même leçon qu'au digest (S6-J4),
#: où le premier commentaire analysait une tendance sur un seul ticket.
MIN_ABSOLUTE = 8


@dataclass
class Anomaly:
    """Un pic constaté sur une catégorie à une heure donnée."""

    scope: str
    bucket_start: datetime
    observed: int
    expected: float
    score: float
    severity: str
    method: str

    def payload(self) -> dict:
        return {
            "observed": self.observed,
            "expected": round(self.expected, 2),
            "score": round(self.score, 2),
            "method": self.method,
        }


def scan(
    grid: list[datetime], series: dict[str, list[int]], lookback: int = 1
) -> list[Anomaly]:
    """Cherche un pic sur les `lookback` dernières heures de chaque catégorie.

    `lookback` vaut 1 en fonctionnement normal — le détecteur tourne toutes les heures et n'a que la
    dernière à juger. Une valeur plus grande sert au rattrapage après un arrêt, et à la démonstration
    quand le pic vient d'être injecté.
    """
    found: list[Anomaly] = []
    for scope, values in series.items():
        for offset in range(1, min(lookback, len(values)) + 1):
            position = len(values) - offset
            anomaly = _judge(scope, grid[position], values, position)
            if anomaly is not None:
                found.append(anomaly)

    # Les plus fortes d'abord : si plusieurs catégories bougent en même temps (panne transverse),
    # c'est celle qui bouge le plus qui oriente le diagnostic.
    found.sort(key=lambda a: a.score, reverse=True)
    return found


def _judge(scope: str, bucket: datetime, values: list[int], position: int) -> Anomaly | None:
    observed = values[position]

    # Le plancher est verifie **avant** tout calcul : c'est une regle metier (« trop peu pour
    # conclure »), pas un cas limite statistique. La placer en tete evite d'avoir a proteger
    # chaque division qui suit.
    if observed < MIN_ABSOLUTE:
        return None

    residuals, expected, method = _residuals(values, position)
    if residuals is None:
        return None

    score = robust_score(residuals, residuals[position])

    # Seuls les **pics** donnent une alerte aujourd'hui.
    #
    # Une chute n'est pas moins intéressante — un canal d'ingestion cassé la produit — mais elle
    # n'est pas détectable de la même façon : un résidu négatif est borné par la valeur attendue
    # elle-même (on ne descend pas sous zéro), donc sur une catégorie qui attend 3 tickets par
    # heure, aucune chute ne peut atteindre le seuil. La traiter correctement demande un plancher
    # de volume attendu distinct, et une mesure à part. Reportée plutôt que bâclée.
    if score < WARNING_SCORE or residuals[position] <= 0:
        return None

    return Anomaly(
        scope=scope,
        bucket_start=bucket,
        observed=observed,
        expected=max(expected, 0.0),
        score=score,
        severity="CRITICAL" if score >= CRITICAL_SCORE else "WARNING",
        method=method,
    )


def robust_score(sample: list[float], value: float) -> float:
    """Score robuste (*modified z-score*) de `value` dans `sample`.

    Renvoie 0 quand la dispersion est négligeable (`MIN_DEVIATION`), c'est-à-dire quand plus de la
    moitié des points sont identiques ou quasi identiques. Le cas est fréquent sur une série creuse,
    et il n'a **pas** de bonne réponse numérique : diviser par zéro donnerait l'infini, et remplacer
    le MAD par une petite constante rendrait le score arbitrairement grand. Renvoyer 0 revient à
    dire « cette série ne permet pas de conclure », et le plancher absolu de `_judge` reste seul
    juge. Une non-réponse assumée vaut mieux qu'un chiffre fabriqué — c'est le même choix que le
    `growth = NULL` du S7-J1.
    """
    if not sample:
        return 0.0
    centre = median(sample)
    deviation = median([abs(x - centre) for x in sample])
    if deviation < MIN_DEVIATION:
        return 0.0
    return MAD_SCALE * (value - centre) / deviation


def _residuals(
    values: list[int], position: int
) -> tuple[list[float] | None, float, str]:
    """Résidus de la série, valeur attendue à `position`, et méthode employée."""
    if len(values) < MIN_HISTORY:
        return None, 0.0, "insufficient_history"

    seasonal = _stl_residuals(values)
    if seasonal is not None:
        return seasonal, values[position] - seasonal[position], "stl"

    fitted = _seasonal_naive(values)
    return [v - f for v, f in zip(values, fitted)], fitted[position], "seasonal_median"


def _stl_residuals(values: list[int]) -> list[float] | None:
    """Résidus d'une décomposition STL, ou `None` si statsmodels est absent ou refuse la série.

    `robust=True` n'est pas un détail : sans lui, un pic passé déforme la forme saisonnière estimée,
    donc la « normale » de cette heure-là, donc la capacité à détecter le pic suivant au même
    moment. La décomposition robuste pondère à la baisse les points aberrants — c'est la même
    préoccupation que le MAD en aval, appliquée une étape plus tôt.
    """
    try:
        import numpy as np
        from statsmodels.tsa.seasonal import STL
    except ImportError:
        logger.info("statsmodels absent : repli sur la mediane saisonniere")
        return None

    try:
        result = STL(np.asarray(values, dtype="float64"), period=SEASON, robust=True).fit()
        return [float(x) for x in result.resid]
    except Exception as exc:  # noqa: BLE001 - serie degeneree, longueur insuffisante
        logger.info("STL indisponible sur cette serie (%s) : repli sur la mediane saisonniere", exc)
        return None


def _seasonal_naive(values: list[int]) -> list[float]:
    """Repli sans dépendance : la valeur attendue d'une heure est la médiane des mêmes heures.

    C'est une désaisonnalisation grossière — elle ignore la tendance — mais elle répond à la bonne
    question (« plus que d'habitude *à cette heure-ci* ? ») et elle est médiane, donc insensible aux
    pics passés. Sur deux semaines d'historique, elle donne 14 points par phase : peu, mais assez
    pour une médiane.
    """
    phases: dict[int, list[int]] = {}
    for position, value in enumerate(values):
        phases.setdefault(position % SEASON, []).append(value)
    reference = {phase: median(points) for phase, points in phases.items()}
    return [float(reference[position % SEASON]) for position in range(len(values))]
