"""Agent Resolution : briques deterministes (S5-J3).

On teste ici tout ce qui **ne depend pas d'un modele** : la validation des citations, le routage du
graphe et le nettoyage de sortie. C'est volontaire et c'est l'essentiel — la garantie qui distingue
une reponse fondee d'une reponse inventee est la validation des citations, et elle doit etre
verifiable sans reseau, sans cle d'API et sans base.

Les noeuds `generate` et `self_check` appellent un LLM : leur comportement est teste au S5-J5 par le
LLM-as-judge sur 50 brouillons, pas ici.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.agents.citations import (
    SENTINEL,
    build,
    extract_markers,
    is_abstention,
    strip_sentinel,
    validate,
)
from app.agents.resolution import (
    MAX_ATTEMPTS,
    _clean,
    _explain_issues,
    _no_passage_reply,
    route_after_check,
)

# --- Extraction des marqueurs ----------------------------------------------


def test_markers_are_deduplicated_in_order():
    assert extract_markers("Selon [2] puis [1], et encore [2].") == [2, 1]


def test_bracketed_text_is_not_a_citation():
    """« [voir ci-dessus] » n'est pas une source : seuls les marqueurs numeriques comptent."""
    assert extract_markers("Comme indique [ci-dessus] et [voir FAQ].") == []


# --- Validation -------------------------------------------------------------


def test_valid_citations_produce_no_issue():
    markers, issues = validate("D'apres [1] et [3], le delai est de 14 jours.", 5)
    assert markers == [1, 3]
    assert issues == []


def test_hallucinated_source_is_caught():
    """Le cas exact que ce controle existe pour attraper : citer une source jamais fournie."""
    markers, issues = validate("Selon [9], c'est possible.", 3)
    assert markers == []
    assert any(i.startswith("invalid_citation") for i in issues)


def test_partially_valid_citation_keeps_the_valid_ones():
    markers, issues = validate("Voir [1] et [8].", 3)
    assert markers == [1]
    assert any(i.startswith("invalid_citation") for i in issues)
    assert "no_valid_citation" not in issues


def test_uncited_claim_is_rejected():
    markers, issues = validate("Le remboursement prend 5 jours ouvres.", 3)
    assert markers == []
    assert issues == ["no_citation"]


def test_honest_abstention_is_accepted_without_citation():
    """Un brouillon qui reconnait ne pas savoir ne doit PAS etre force a inventer une source."""
    for text in [
        "Je n'ai pas trouve d'information couvrant cette demande.",
        "Aucune information disponible sur ce point dans la documentation.",
        "I could not find information covering this request.",
    ]:
        _markers, issues = validate(text, 3)
        assert issues == [], text
        assert is_abstention(text), text


# --- Construction des citations --------------------------------------------


def test_citations_carry_what_the_ui_needs():
    """`source` et `heading` permettront le surlignage du passage exact (S5-J4)."""
    passages = [
        {"id": 11, "source": "faq-facturation.md", "heading": "Facturation > Remboursement", "content": "Texte long." * 40},
        {"id": 12, "source": "faq-compte.md", "heading": "Compte > Mot de passe", "content": "Autre."},
    ]
    built = build([2], passages)
    assert len(built) == 1
    assert built[0]["chunk_id"] == 12
    assert built[0]["source"] == "faq-compte.md"
    assert built[0]["heading"] == "Compte > Mot de passe"
    # L'extrait est borne : on ne recopie pas un fragment entier dans chaque citation.
    assert len(build([1], passages)[0]["excerpt"]) <= 280


# --- Routage du graphe ------------------------------------------------------


def test_clean_draft_is_accepted():
    assert route_after_check({"issues": [], "attempts": 1}) == "accept"


def test_faulty_draft_is_regenerated():
    assert route_after_check({"issues": ["not_grounded"], "attempts": 1}) == "retry"


def test_retry_budget_is_bounded():
    """Sans borne, un modele qui echoue systematiquement boucle indefiniment et brule le quota."""
    assert route_after_check({"issues": ["not_grounded"], "attempts": MAX_ATTEMPTS}) == "give_up"
    assert route_after_check({"issues": ["not_grounded"], "attempts": MAX_ATTEMPTS + 1}) == "give_up"


# --- Consignes de re-generation --------------------------------------------


def test_issues_become_actionable_instructions():
    """Re-generer sans dire ce qui n'allait pas redonnerait le meme brouillon."""
    text = _explain_issues(["invalid_citation:9", "not_grounded"])
    assert "9" in text
    assert "passages" in text.lower()


def test_unknown_issue_still_yields_an_instruction():
    assert _explain_issues(["something_new"]).strip() != ""


# --- Nettoyage de sortie ----------------------------------------------------


def test_conversational_preamble_is_removed():
    """Les modeles ajoutent « Voici le brouillon : » malgre la consigne — ca ne part pas au client."""
    assert _clean("Voici le brouillon de reponse :\nBonjour, [1]").startswith("Bonjour")
    assert _clean("Here is the draft reply:\nHello, [1]").startswith("Hello")


def test_code_fences_are_stripped():
    assert _clean("```\nBonjour [1]\n```") == "Bonjour [1]"


# --- Abstention : regression du S5-J3 ---------------------------------------


def test_real_world_abstention_wording_is_recognised():
    """Regression : cette formulation exacte a echoue en verification du S5-J3.

    Le motif attendait « pas d'information » ; le modele a ecrit « les informations ... ne sont
    pas disponibles ». Consequence : 3 generations au lieu d'une, et un drapeau « faible
    confiance » leve sur un brouillon pourtant irreprochable.
    """
    text = (
        "Je m'excuse, mais les informations fournies concernant l'adoption d'animaux "
        "ne sont pas disponibles dans les passages fournis."
    )
    assert is_abstention(text)
    _markers, issues = validate(text, 5)
    assert issues == []


def test_sentinel_is_recognised_whatever_the_wording():
    """Le jeton rend la detection exacte : plus de course aux formulations."""
    assert is_abstention(f"{SENTINEL} Desole, je ne peux pas repondre.")
    assert is_abstention(f"{SENTINEL} Sorry.")
    # Une tournure inedite, sans jeton et sans motif connu, n'est pas reconnue — et c'est le
    # comportement sur : mieux vaut une re-generation de trop qu'une hallucination laissee passer.
    assert not is_abstention("Bonjour, je vais me renseigner et revenir vers vous.")


def test_sentinel_never_reaches_the_customer():
    assert SENTINEL not in strip_sentinel(f"{SENTINEL} Desole, aucune information disponible.")
    assert strip_sentinel(f"{SENTINEL} Desole.") == "Desole."


def test_plain_answer_is_not_mistaken_for_abstention():
    """Un faux positif serait pire : un vrai brouillon echapperait a toute verification."""
    for text in [
        "Le remboursement est traite sous 5 jours ouvres [1].",
        "Votre commande a bien ete expediee, voici le suivi [2].",
        "Please retry with another payment method [1].",
    ]:
        assert not is_abstention(text), text


def test_abstention_message_is_written_by_code_not_by_the_model():
    """Le modele decide de s'abstenir ; le texte du refus est fixe.

    Regression du S5-J3 : laisse libre, le modele produisait « Je suis la pour vous aider a la
    place ou vous me contactez » — du remplissage grammaticalement casse. Rediger un refus ne
    demande aucun jugement, donc aucun modele.
    """
    fr = _no_passage_reply("fr")
    en = _no_passage_reply("en")
    assert "manuellement" in fr
    assert "documentation" in fr
    assert "manually" in en
    # Le message d'abstention ne doit lui-meme jamais etre pris pour une reponse citee.
    for text in (fr, en):
        _markers, issues = validate(text, 5)
        assert issues == []
        assert is_abstention(text)
