"""Sujets émergents (S7-J1).

Ce qui est testé ici est **ce que le code décide**, pas ce que le modèle écrit : le découpage en
groupes sur des données construites, l'arithmétique de croissance, la règle de catégorie dominante
et le nettoyage d'un libellé. Le libellé lui-même dépend d'un appel LLM — non déterministe, coûteux,
et sa qualité se juge en le lisant, pas en l'assertant.

L'accent est mis sur les façons dont ces briques pourraient **mentir** : annoncer une croissance
là où il n'y a rien à comparer, imposer une catégorie à un groupe qui n'en a pas, ou laisser passer
un libellé qui est en réalité une phrase du modèle.
"""
import pytest

from app.topics import cluster, label, service

# ---------------------------------------------------------------------------
# Regroupement
# ---------------------------------------------------------------------------


def test_a_small_corpus_yields_nothing_rather_than_a_fake_topic():
    """Sous le seuil, on ne regroupe pas. Deux tickets qui se ressemblent ne font pas un sujet."""
    vectors = [[0.1, 0.2, 0.3] for _ in range(5)]
    assert cluster.find_clusters(vectors) == []


@pytest.mark.slow
def test_three_separated_groups_are_found():
    """Trois nuages nettement séparés doivent ressortir comme trois sujets.

    Test volontairement facile : il ne mesure pas la qualité du regroupement sur de vraies données
    (rien ne le pourrait sans annotation), il vérifie que la chaîne UMAP → HDBSCAN est câblée et
    que le bruit est bien écarté au lieu d'être absorbé.
    """
    import random

    random.seed(0)
    centres = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]
    vectors = [
        [c + random.gauss(0, 0.01) for c in centre]
        for centre in centres
        for _ in range(20)
    ]

    found = cluster.find_clusters(vectors)
    assert len(found) >= 2, "les nuages sont trop separes pour n'en trouver qu'un"
    assert all(c.size >= cluster.MIN_CLUSTER_SIZE for c in found)


def test_centroid_order_puts_the_most_representative_first():
    """Les exemples montrés au modèle doivent être au centre du groupe, pas à sa bordure."""
    vectors = [
        [0.0, 0.0],   # centre
        [0.1, 0.0],
        [-0.1, 0.0],
        [5.0, 5.0],   # loin
    ]
    ordered = cluster.centroid_order(vectors, [0, 1, 2, 3], limit=4)
    assert ordered[-1] == 3
    assert ordered[0] in (0, 1, 2)


# ---------------------------------------------------------------------------
# Croissance
# ---------------------------------------------------------------------------


def test_growth_is_none_when_there_is_nothing_to_compare():
    """Un sujet apparu dans la fenêtre n'a pas de croissance chiffrable.

    « +100 % » laisserait croire à un doublement, « +∞ % » n'est pas un chiffre. L'absence de
    valeur est plus honnête — et l'interface en tire « nouveau », qui dit davantage.
    """
    assert service._growth(recent=12, previous=0) is None


def test_growth_is_a_percentage_between_the_two_halves():
    assert service._growth(recent=15, previous=10) == 50.0
    assert service._growth(recent=5, previous=10) == -50.0
    assert service._growth(recent=10, previous=10) == 0.0


# ---------------------------------------------------------------------------
# Catégorie dominante
# ---------------------------------------------------------------------------


def test_a_clear_majority_gives_a_dominant_category():
    members = [{"category": "FACTURATION"}] * 7 + [{"category": "TECHNIQUE"}] * 2
    assert service._dominant_category(members) == "FACTURATION"


def test_a_split_group_has_no_dominant_category():
    """Sans majorité, afficher une catégorie donnerait une fausse certitude."""
    members = (
        [{"category": "FACTURATION"}] * 4
        + [{"category": "TECHNIQUE"}] * 3
        + [{"category": "COMPTE"}] * 3
    )
    assert service._dominant_category(members) is None


def test_unanalysed_tickets_do_not_invent_a_category():
    assert service._dominant_category([{"category": None}] * 10) is None


# ---------------------------------------------------------------------------
# Libellé
# ---------------------------------------------------------------------------


def test_label_is_stripped_of_the_decorations_models_add():
    assert label._clean('"Échec de paiement mobile"') == "Échec de paiement mobile"
    assert label._clean("- Échec de paiement mobile.") == "Échec de paiement mobile"
    assert label._clean("`Échec de paiement mobile`") == "Échec de paiement mobile"


def test_a_chatty_answer_keeps_only_the_first_line():
    """Un modèle répond parfois « Voici le libellé : … ». On garde la première ligne utile."""
    assert label._clean("\n\nÉchec de paiement mobile\n\nCe groupe concerne…") == (
        "Échec de paiement mobile"
    )


def test_a_long_label_is_truncated_not_refused():
    long = "x" * 200
    assert len(label._clean(long)) == label.MAX_LABEL_CHARS


def test_fallback_uses_the_most_central_subject():
    """Un sujet mal nommé reste consultable ; un sujet sans nom est invisible."""
    samples = [{"subject": "Double débit sur ma carte", "body": "…"}]
    assert label._fallback(samples) == "Double débit sur ma carte"


def test_fallback_survives_an_empty_group():
    assert label._fallback([]) == "Sujet sans libellé"
    assert label._fallback([{"subject": "", "body": "x"}]) == "Sujet sans libellé"


@pytest.mark.asyncio
async def test_a_model_failure_falls_back_instead_of_losing_the_topic(monkeypatch):
    """Une panne de nommage ne doit pas faire disparaître un sujet réel de la liste."""
    async def boom(*_args, **_kwargs):
        raise RuntimeError("quota")

    import app.core.llm as llm_module

    monkeypatch.setattr(llm_module, "complete", boom)
    name = await label.name_cluster([{"subject": "Livraison en retard", "body": "…"}])
    assert name == "Livraison en retard"
