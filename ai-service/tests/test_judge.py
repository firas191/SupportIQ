"""Parties déterministes du juge de brouillons (S5-J5).

Ce qui est testé ici est exactement ce qui **peut régresser en silence** lors d'un remaniement :
l'analyse du verdict, l'agrégation, le verrou d'exactitude, l'exclusion des abstentions. Aucun appel
réseau, donc ces garanties tiennent en CI — là où la campagne complète, qui demande une base peuplée
et des clés d'API, ne peut pas tourner.

Même ligne de partage qu'au S5-J3 pour l'agent : une garantie qu'on ne peut pas tester sans clé
d'API n'en est pas une.
"""
from app.agents.judge import Verdict, aggregate, build_prompt, is_judgeable, parse_verdict

# ---------------------------------------------------------------------------
# Analyse du verdict
# ---------------------------------------------------------------------------


def test_parses_plain_json():
    verdict = parse_verdict('{"accuracy": 2, "completeness": 1, "tone": 2, "reason": "ok"}')
    assert verdict is not None
    assert (verdict.accuracy, verdict.completeness, verdict.tone) == (2, 1, 2)


def test_parses_json_wrapped_in_a_code_fence():
    raw = 'Here is my grading:\n```json\n{"accuracy": 1, "completeness": 2, "tone": 2}\n```\nDone.'
    verdict = parse_verdict(raw)
    assert verdict is not None
    assert verdict.accuracy == 1


def test_parses_json_surrounded_by_chatter():
    # Les modèles ajoutent du commentaire malgré la consigne : tolérant sur la forme.
    verdict = parse_verdict('Sure! {"accuracy": 0, "completeness": 0, "tone": 1} Hope this helps.')
    assert verdict is not None
    assert verdict.accuracy == 0


def test_rejects_a_grade_out_of_range():
    # Strict sur le fond : ramener silencieusement un 4 à 2 fabriquerait une donnée que le juge
    # n'a jamais produite, et la moyenne serait contaminée sans trace.
    assert parse_verdict('{"accuracy": 4, "completeness": 2, "tone": 2}') is None


def test_rejects_a_missing_criterion():
    assert parse_verdict('{"accuracy": 2, "tone": 2}') is None


def test_rejects_a_non_json_answer():
    assert parse_verdict("The draft looks good to me.") is None


# ---------------------------------------------------------------------------
# Agrégation
# ---------------------------------------------------------------------------


def test_perfect_draft_scores_one():
    assert aggregate(Verdict(accuracy=2, completeness=2, tone=2)) == 1.0


def test_score_is_the_mean_of_the_three_criteria():
    assert aggregate(Verdict(accuracy=2, completeness=1, tone=2)) == round(5 / 6, 2)


def test_zero_accuracy_locks_the_whole_score_to_zero():
    # Le point central de la grille : un brouillon bien écrit qui affirme un fait absent des
    # sources est inutilisable. Une moyenne lui donnerait 0,67 — un chiffre rassurant sur un
    # texte à jeter.
    assert aggregate(Verdict(accuracy=0, completeness=2, tone=2)) == 0.0


def test_a_single_unsupported_detail_is_not_fatal():
    # Exactitude 1 = travail de relecture, pas information fausse. La note doit rester exploitable.
    assert aggregate(Verdict(accuracy=1, completeness=2, tone=2)) > 0.5


# ---------------------------------------------------------------------------
# Périmètre du jugement
# ---------------------------------------------------------------------------


def test_an_abstention_is_not_judged():
    # La noter donnerait complétude 0, donc pénaliserait le comportement recherché : l'agrégat
    # mesurerait la couverture de la base de connaissances déguisée en qualité de rédaction.
    assert is_judgeable(abstained=True, passages=[{"content": "x"}]) is False


def test_a_draft_without_passages_is_not_judged():
    assert is_judgeable(abstained=False, passages=[]) is False


def test_a_normal_draft_is_judged():
    assert is_judgeable(abstained=False, passages=[{"content": "x"}]) is True


# ---------------------------------------------------------------------------
# Prompt
# ---------------------------------------------------------------------------


def test_prompt_hides_the_self_check_signals():
    """Le juge ne doit voir ni le drapeau de confiance ni le nombre de tentatives.

    Ils prédisent en partie la note : les montrer transformerait la mesure en prophétie
    auto-réalisatrice, alors que c'est justement leur pouvoir prédictif qu'on veut établir.
    """
    prompt = build_prompt(
        "Je veux un remboursement",
        [{"heading": "Facturation", "content": "Remboursement sous 7 jours."}],
        "Bonjour, comptez 7 jours [1].",
        "formal",
    )
    lowered = prompt.lower()
    assert "low_confidence" not in lowered
    assert "attempt" not in lowered
    assert "confidence" not in lowered


def test_prompt_marks_untrusted_data():
    """Le message client et les passages arrivent dans le prompt : injection à neutraliser."""
    prompt = build_prompt("q", [{"heading": "h", "content": "c"}], "d", "formal")
    assert "<customer_message>" in prompt
    assert "<passages>" in prompt
    assert "untrusted" in prompt.lower()
