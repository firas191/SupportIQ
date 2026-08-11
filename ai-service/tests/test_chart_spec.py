"""Dérivation du graphique à partir de la forme du résultat (S6-J2).

Ces règles sont **entièrement déterministes** : aucun appel de modèle, aucune base. C'est
précisément pour cela qu'elles ont été confiées au code plutôt qu'au modèle, et c'est pour cela
qu'elles tiennent en CI.

Ce qui est vérifié n'est pas « le bon graphique sort » mais les trois façons dont la règle pourrait
mal tourner : promettre un graphique là où il n'y a rien à voir, prendre un booléen pour une
mesure, et choisir une courbe là où la continuité n'a pas de sens.
"""
from app.agents.chart import derive


def test_a_single_number_gets_no_chart():
    # « 412 tickets » se lit mieux en chiffre qu'en barre unique.
    spec = derive(["total_tickets"], [[412]])
    assert spec["type"] == "none"
    assert spec["reason"] == "single_value"


def test_empty_result_gets_no_chart():
    assert derive(["category", "nb"], [])["type"] == "none"
    assert derive([], [])["type"] == "none"


def test_a_result_without_numbers_gets_no_chart():
    spec = derive(["category", "status"], [["FACTURATION", "NEW"]])
    assert spec["type"] == "none"
    assert spec["reason"] == "no_numeric_column"


def test_categories_give_a_bar_chart():
    spec = derive(["category", "nb"], [["FACTURATION", 3], ["TECHNIQUE", 2]])
    assert spec["type"] == "bar"
    assert spec["x"] == "category"
    assert spec["y"] == "nb"


def test_a_day_column_gives_a_line_chart():
    # La continuité entre deux jours a un sens ; entre deux catégories, non.
    spec = derive(["day", "nb"], [["2026-08-01", 12], ["2026-08-02", 15]])
    assert spec["type"] == "line"
    assert spec["x"] == "day"


def test_an_iso_date_is_recognised_even_under_another_name():
    # Les dates traversent la couche d'exécution en chaînes : le type est déjà perdu, on
    # reconnaît la forme.
    spec = derive(["periode", "nb"], [["2026-08-01", 12], ["2026-08-02", 15]])
    assert spec["type"] == "line"


def test_hour_of_day_is_temporal():
    spec = derive(["hour_of_day", "ticket_count"], [[9, 12], [10, 30]])
    assert spec["type"] == "line"
    assert spec["x"] == "hour_of_day"


def test_a_temporal_column_wins_over_a_categorical_one():
    # Quand un résultat porte un jour ET une catégorie, la question est presque toujours
    # « comment ça évolue ».
    spec = derive(["day", "category", "nb"],
                  [["2026-08-01", "FACTURATION", 3], ["2026-08-02", "COMPTE", 4]])
    assert spec["x"] == "day"
    assert spec["type"] == "line"


def test_booleans_are_not_measures():
    """En Python `True` est un entier. Tracer une courbe de vrai et faux n'a aucun sens."""
    spec = derive(["abstained", "low_confidence"], [[True, False], [False, True]])
    assert spec["type"] == "none"
    assert spec["reason"] == "no_numeric_column"


def test_too_many_categories_gives_no_chart():
    rows = [[f"cat-{i}", i] for i in range(60)]
    spec = derive(["category", "nb"], rows)
    assert spec["type"] == "none"
    assert spec["reason"] == "too_many_categories"


def test_many_points_are_fine_on_a_time_series():
    # Le plafond de catégories ne s'applique pas au temps : une courbe de 60 jours se lit bien.
    rows = [[f"2026-08-{(i % 28) + 1:02d}", i] for i in range(60)]
    spec = derive(["day", "nb"], rows)
    assert spec["type"] == "line"


def test_nulls_do_not_break_numeric_detection():
    spec = derive(["category", "note"], [["A", None], ["B", 0.8]])
    assert spec["type"] == "bar"
    assert spec["y"] == "note"


def test_a_reason_is_always_provided():
    """L'interface doit pouvoir écrire « pas de graphique parce que… » au lieu d'un cadre vide,
    qui se lit comme une panne."""
    for spec in (
        derive([], []),
        derive(["n"], [[1]]),
        derive(["category", "nb"], [["A", 1]]),
    ):
        assert spec["reason"]
