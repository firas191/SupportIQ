"""Risque de dépassement SLA (S7-J3).

Ce qui est testé ici n'est pas la qualité prédictive du modèle — elle se mesure dans
`ml/train_sla_risk.py`, sur des données simulées, et ne veut rien dire de plus que ce que ce script
annonce. Ce sont les trois choses qui doivent tenir **quoi qu'il arrive au modèle** :

1. **Le contrat de variables** — l'ordre des colonnes est la seule chose qui relie l'entraînement au
   service. S'il change d'un côté sans l'autre, rien ne plante : le modèle répond, et il répond
   n'importe quoi.
2. **Le repli** — sans artefact, le service doit produire un score utilisable, pas une erreur.
3. **La monotonie de la règle** — un ticket plus proche de son échéance ne doit jamais recevoir un
   risque plus faible. C'est la seule propriété qu'un utilisateur remarquera immédiatement si elle
   est fausse.
"""
from datetime import datetime, timedelta, timezone

from app.sla import features, model

NOW = datetime(2026, 8, 21, 12, 0, tzinfo=timezone.utc)


def ticket(**overrides) -> dict:
    base = {
        "created_at": NOW - timedelta(hours=2),
        "sla_due_at": NOW + timedelta(hours=22),
        "priority": "MEDIUM",
        "category": "FACTURATION",
        "sentiment": "NEU",
        "source": "FILE",
        "backlog": 10,
    }
    base.update(overrides)
    return base


# ---------------------------------------------------------------------------
# Contrat de variables
# ---------------------------------------------------------------------------


def test_the_vector_has_exactly_one_value_per_declared_column():
    """Le decalage entrainement/service commence toujours par une longueur qui diverge."""
    assert len(features.build(ticket(), NOW)) == len(features.COLUMNS)


def test_categorical_indices_point_at_the_categorical_columns():
    names = [features.COLUMNS[i] for i in features.CATEGORICAL_INDICES]
    assert set(names) == {"priority", "category", "sentiment", "source"}


def test_an_unknown_modality_becomes_minus_one_not_an_error():
    """Un ticket non analysé n'a ni catégorie ni humeur — et c'est une information.

    `-1` est une modalité comme une autre pour LightGBM : « personne n'a encore regardé ce
    ticket ». Lever une exception ferait échouer le lot entier sur un ticket tout juste arrivé.
    """
    assert features.encode("category", None) == -1
    assert features.encode("category", "INEXISTANTE") == -1
    assert features.encode("priority", "HIGH") == 0


def test_a_missing_deadline_falls_back_on_the_priority_budget():
    """Sans échéance, on retombe sur ce que la politique aurait posé.

    Inventer `hours_remaining = 0` ferait passer pour dépassé un ticket qui n'a jamais été daté —
    et il y en a, tant que le lot de scoring n'a pas vu passer une analyse.
    """
    vector = features.build(ticket(sla_due_at=None, priority="HIGH"), NOW)
    remaining = vector[features.COLUMNS.index("hours_remaining")]
    # HIGH = 4 h de budget, le ticket a 2 h : il reste 2 h.
    assert abs(remaining - 2.0) < 0.01


def test_a_passed_deadline_gives_a_negative_remaining_time():
    """On ne borne pas à zéro : « en retard de 2 h » et « de 40 h » ne sont pas le même état."""
    vector = features.build(ticket(sla_due_at=NOW - timedelta(hours=40)), NOW)
    assert vector[features.COLUMNS.index("hours_remaining")] < -39


# ---------------------------------------------------------------------------
# Règle de repli
# ---------------------------------------------------------------------------


def test_without_an_artifact_the_service_still_scores():
    """Le repli n'est pas un mode dégradé exceptionnel : c'est le mode **par défaut**.

    Aucun artefact n'est déployé tant que personne n'a lancé l'entraînement, et la file doit rester
    triable ce jour-là.
    """
    vector = features.build(ticket(), NOW)
    risk, origin = model.score(vector, "MEDIUM")

    assert 0.0 <= risk <= 1.0
    assert origin in {"rules", "lightgbm"}


def test_the_rule_is_monotone_in_elapsed_time():
    """Plus le ticket vieillit, plus le risque monte. Jamais l'inverse.

    C'est la seule propriété qu'un utilisateur remarquera tout de suite si elle est fausse — et
    c'est aussi ce qui rend la règle défendable comme baseline plutôt que comme bouche-trou.
    """
    scores = [
        model.rule_score(features.build(ticket(created_at=NOW - timedelta(hours=h)), NOW), "MEDIUM")
        for h in (1, 6, 12, 20, 24, 48)
    ]
    assert scores == sorted(scores)


def test_the_rule_saturates_at_one_once_the_deadline_is_passed():
    """Un ticket dont l'échéance est passée dépasse effectivement son SLA : 1, pas 1,8."""
    vector = features.build(ticket(created_at=NOW - timedelta(hours=200)), NOW)
    assert model.rule_score(vector, "MEDIUM") == 1.0


def test_priority_changes_the_budget_hence_the_risk():
    """Six heures écoulées : confortable en priorité basse, déjà dépassé en priorité haute."""
    vector = features.build(ticket(created_at=NOW - timedelta(hours=6)), NOW)

    assert model.rule_score(vector, "LOW") < 0.15
    assert model.rule_score(vector, "HIGH") == 1.0


# ---------------------------------------------------------------------------
# Calibration
# ---------------------------------------------------------------------------


def test_calibration_interpolates_between_the_table_points(monkeypatch):
    monkeypatch.setattr(model, "_calibration", [(0.0, 0.0), (0.5, 0.2), (1.0, 1.0)])

    assert model._calibrate(0.0) == 0.0
    assert abs(model._calibrate(0.25) - 0.1) < 1e-9
    assert abs(model._calibrate(0.75) - 0.6) < 1e-9


def test_calibration_clamps_outside_the_table(monkeypatch):
    """Un score hors de la plage apprise prend la valeur du bord, jamais une extrapolation.

    Extrapoler au-delà des données observées est exactement la façon dont une calibration se met à
    produire des probabilités supérieures à 1.
    """
    monkeypatch.setattr(model, "_calibration", [(0.2, 0.1), (0.8, 0.9)])

    assert model._calibrate(-5.0) == 0.1
    assert model._calibrate(99.0) == 0.9


def test_without_a_calibration_table_the_raw_score_is_clamped(monkeypatch):
    monkeypatch.setattr(model, "_calibration", None)

    assert model._calibrate(1.7) == 1.0
    assert model._calibrate(-0.3) == 0.0
    assert model._calibrate(0.42) == 0.42
