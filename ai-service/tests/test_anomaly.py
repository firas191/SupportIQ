"""Détection d'anomalies de volume (S7-J2).

Le détecteur est un module **pur** : on peut donc lui donner des séries dont on connaît la réponse,
ce qui est rare dans ce projet et qu'il faut exploiter.

Les cas testés ne sont pas « est-ce que ça détecte » — c'est le cas facile. Ce sont les quatre
façons dont un détecteur d'anomalies devient inutile :

1. il se déclenche sur le rythme normal (tous les matins à 9 h) ;
2. il rate le pic parce que le pic lui-même a gonflé sa mesure de dispersion ;
3. il se déclenche sur trois tickets un dimanche ;
4. il se déclenche sur une série creuse, où toute variation paraît infinie.

Les séries de test portent du **bruit déterministe** (graine fixe). Une série parfaitement
régulière serait un cas pathologique dans les deux sens : sa dispersion est nulle, donc tout score
y est soit infini, soit nul. Aucune donnée réelle ne ressemble à cela, et tester dessus mesurerait
le comportement du détecteur sur un cas qui ne se produit jamais.
"""
import random
from datetime import datetime, timedelta, timezone

from app.anomaly import detect

START = datetime(2026, 8, 1, tzinfo=timezone.utc)

#: Index (depuis la fin) d'une heure **de journée** dans la grille : 336 - 8 = 328, phase 16.
DAY_OFFSET = 8


def grid(n: int) -> list[datetime]:
    return [START + timedelta(hours=i) for i in range(n)]


def daily_shape(days: int = 14, night: int = 3, day: int = 20, seed: int = 7) -> list[int]:
    """Série horaire avec un rythme jour/nuit et un bruit reproductible.

    C'est le rythme qui piège un score naïf : sans désaisonnalisation, chaque matinée se situe à
    plusieurs écarts de la moyenne journalière.
    """
    rng = random.Random(seed)
    values = []
    for _ in range(days):
        for hour in range(24):
            base = day if 7 <= hour < 17 else night
            values.append(max(0, base + rng.randint(-2, 2)))
    return values


# ---------------------------------------------------------------------------
# Le rythme normal n'est pas une anomalie
# ---------------------------------------------------------------------------


def test_the_daily_rhythm_alone_never_triggers():
    """Le cœur du sujet : 20 tickets à 10 h est la normale, pas un pic.

    C'est l'alerte qui se déclencherait tous les jours à la même heure — donc celle qu'un
    responsable désactive la première semaine, en emportant les vraies avec elle.
    """
    values = daily_shape()
    assert detect.scan(grid(len(values)), {"TECHNIQUE": values}, lookback=24) == []


def test_a_spike_on_top_of_the_rhythm_is_found():
    values = daily_shape()
    values[-DAY_OFFSET] = 140  # sept fois la normale de cette heure-ci
    found = detect.scan(grid(len(values)), {"TECHNIQUE": values}, lookback=DAY_OFFSET)

    assert len(found) == 1
    assert found[0].scope == "TECHNIQUE"
    assert found[0].observed == 140
    assert found[0].severity == "CRITICAL"


def test_a_night_spike_is_found_even_though_the_count_would_be_normal_by_day():
    """25 tickets à 3 h du matin est anormal ; 25 à 10 h ne le serait pas.

    C'est ce que la désaisonnalisation achète. Sans elle, il faudrait un seuil par heure, écrit à
    la main, et faux dès que les habitudes changent.
    """
    values = daily_shape()
    values[-1] = 25  # derniere heure de la grille : phase 23, donc une heure creuse
    found = detect.scan(grid(len(values)), {"TECHNIQUE": values}, lookback=1)

    assert len(found) == 1
    assert found[0].expected < 10


# ---------------------------------------------------------------------------
# Robustesse de l'estimateur
# ---------------------------------------------------------------------------


def test_the_mad_is_not_blinded_by_the_spikes_it_must_detect():
    """Le point de rupture, démontré sur des chiffres.

    Trente points autour de 10, puis cinq pics à 200. Un z-score classique compare 200 à une
    moyenne et à un écart-type que **ces pics ont eux-mêmes gonflés** : il retombe sous 3, le seuil
    usuel, et le détecteur est aveuglé par exactement ce qu'il devait voir. Le MAD ne bouge pas —
    il faudrait corrompre la moitié des points pour le déplacer.
    """
    sample = [10.0, 11.0, 9.0, 12.0, 8.0] * 6 + [200.0] * 5

    mean = sum(sample) / len(sample)
    variance = sum((x - mean) ** 2 for x in sample) / len(sample)
    naive_z = (200.0 - mean) / (variance ** 0.5)

    assert naive_z < 3.0
    assert detect.robust_score(sample, 200.0) > detect.CRITICAL_SCORE


def test_a_flat_series_yields_no_score_rather_than_infinity():
    """Dispersion négligeable : aucune réponse numérique n'est honnête.

    Diviser par zéro donne l'infini ; remplacer le MAD par une petite constante rend le score
    arbitrairement grand. Renvoyer 0 dit « cette série ne permet pas de conclure » — même choix que
    le `growth = NULL` du S7-J1.
    """
    assert detect.robust_score([5.0] * 30, 900.0) == 0.0


def test_a_sparse_category_does_not_alert_on_two_tickets():
    """Une catégorie presque toujours vide : son MAD est nul, tout écart y paraît infini.

    C'est le faux positif le plus vicieux, parce qu'il vise précisément les catégories rares —
    celles dont l'alerte semblerait la plus intéressante.
    """
    values = [0] * (14 * 24)
    values[-1] = 2
    assert detect.scan(grid(len(values)), {"RECLAMATION": values}, lookback=1) == []


# ---------------------------------------------------------------------------
# Planchers et garde-fous
# ---------------------------------------------------------------------------


def test_below_the_absolute_floor_nothing_is_reported():
    values = daily_shape(night=0, day=1)
    values[-DAY_OFFSET] = detect.MIN_ABSOLUTE - 1
    assert detect.scan(grid(len(values)), {"COMPTE": values}, lookback=DAY_OFFSET) == []


def test_too_little_history_is_a_refusal_not_a_guess():
    """Moins de deux périodes : la forme saisonnière n'est pas estimable. On ne devine pas."""
    values = [5] * 30 + [400]
    assert detect.scan(grid(len(values)), {"COMPTE": values}, lookback=1) == []


def test_a_drop_is_not_reported_today():
    """Décision assumée : seuls les pics alertent aujourd'hui (voir le commentaire de `_judge`).

    La série est volontairement à fort volume — sur une catégorie qui attend 3 tickets par heure,
    aucune chute ne pourrait de toute façon atteindre le seuil, puisqu'on ne descend pas sous zéro.
    Ici la chute est massive et parfaitement mesurable, et elle n'alerte pas : c'est bien un choix
    de périmètre, pas une limite subie.
    """
    values = daily_shape(night=20, day=200)
    values[-DAY_OFFSET] = 50
    assert detect.scan(grid(len(values)), {"TECHNIQUE": values}, lookback=DAY_OFFSET) == []


# ---------------------------------------------------------------------------
# Sortie
# ---------------------------------------------------------------------------


def test_the_result_carries_the_numbers_a_human_can_read():
    """Un score seul n'est pas lisible. « 140 là où 20 étaient attendus » l'est."""
    values = daily_shape()
    values[-DAY_OFFSET] = 140
    anomaly = detect.scan(grid(len(values)), {"TECHNIQUE": values}, lookback=DAY_OFFSET)[0]

    payload = anomaly.payload()
    assert payload["observed"] == 140
    assert payload["expected"] > 0
    assert payload["method"] in {"stl", "seasonal_median"}


def test_strongest_anomalies_come_first():
    """Sur une panne transverse, c'est la catégorie qui bouge le plus qui oriente le diagnostic."""
    small = daily_shape()
    small[-DAY_OFFSET] = 60
    big = daily_shape()
    big[-DAY_OFFSET] = 300

    found = detect.scan(
        grid(len(small)), {"COMPTE": small, "TECHNIQUE": big}, lookback=DAY_OFFSET
    )
    assert [a.scope for a in found] == ["TECHNIQUE", "COMPTE"]


def test_the_seasonal_fallback_works_without_statsmodels(monkeypatch):
    """Le repli doit être **exercé**, pas seulement écrit : c'est le chemin pris en son absence."""
    monkeypatch.setattr(detect, "_stl_residuals", lambda values: None)

    values = daily_shape()
    values[-DAY_OFFSET] = 140
    found = detect.scan(grid(len(values)), {"TECHNIQUE": values}, lookback=DAY_OFFSET)

    assert len(found) == 1
    assert found[0].method == "seasonal_median"
    # Mediane des heures de meme phase : le niveau de journee, autour de 20.
    assert 15 <= found[0].expected <= 25
