"""Retrieval hybride : tokenisation lexicale et fusion RRF (S5-J2).

Les deux briques testées ici sont **pures** — ni base, ni modèle, ni réseau. C'est délibéré : la
fusion est le cœur du J2 et son comportement doit être vérifiable sans infrastructure, sinon on ne
la teste jamais vraiment.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.kb.lexical import tokenize
from app.kb.retrieval import reciprocal_rank_fusion as rrf

# --- Tokenisation ----------------------------------------------------------


def test_accents_are_removed():
    """Un client qui ecrit « delai » doit trouver « delai » — et « retractation » aussi."""
    assert tokenize("Délai de rétractation") == tokenize("Delai de retractation")


def test_stopwords_are_dropped():
    """« comment » et « un » ne doivent pas peser dans le score d'une question."""
    terms = tokenize("Comment obtenir un remboursement ?")
    assert terms == ["obtenir", "remboursement"]


def test_digits_are_kept():
    """Les termes rares — codes d'erreur, montants — sont precisement ce que BM25 apporte."""
    assert "500" in tokenize("erreur 500 au paiement")
    assert "14" in tokenize("delai de 14 jours")


def test_english_is_handled_too():
    terms = tokenize("How do I cancel my order?")
    assert "cancel" in terms and "order" in terms
    assert "how" not in terms


# --- Fusion RRF ------------------------------------------------------------


def test_document_found_by_both_engines_wins():
    """C'est toute la raison d'etre de la fusion : l'accord entre moteurs vaut mieux qu'un score."""
    dense = [{"id": 1}, {"id": 2}, {"id": 3}]
    sparse = [{"id": 3}, {"id": 1}, {"id": 9}]
    fused = rrf([dense, sparse])
    assert fused[0]["id"] == 1  # 1er + 2e
    assert fused[1]["id"] == 3  # 3e + 1er
    assert [d["id"] for d in fused[-2:]] == [2, 9]  # trouves par un seul moteur


def test_fusion_ignores_score_scales():
    """Un BM25 a 400 ne doit pas ecraser un cosinus a 0,85 : seul le rang compte."""
    dense = [{"id": 1, "similarity": 0.85}, {"id": 2, "similarity": 0.84}]
    sparse = [{"id": 2, "score": 412.0}, {"id": 1, "score": 3.1}]
    fused = rrf([dense, sparse])
    # 1 est 1er/2e, 2 est 2e/1er : parfaitement symetrique, donc scores egaux.
    assert fused[0]["fusion_score"] == fused[1]["fusion_score"]


def test_single_list_keeps_its_order():
    ranked = [{"id": 7}, {"id": 8}, {"id": 9}]
    assert [d["id"] for d in rrf([ranked])] == [7, 8, 9]


def test_empty_input_is_safe():
    assert rrf([]) == []
    assert rrf([[], []]) == []


def test_k_dampens_the_head():
    """Un k plus grand aplatit l'ecart entre la 1re et la 2e place : c'est son role."""
    ranked = [{"id": 1}, {"id": 2}]
    tight = rrf([ranked], k=1)
    loose = rrf([ranked], k=1000)
    ratio_tight = tight[0]["fusion_score"] / tight[1]["fusion_score"]
    ratio_loose = loose[0]["fusion_score"] / loose[1]["fusion_score"]
    assert ratio_tight > ratio_loose


def test_documents_keep_their_fields():
    """La fusion enrichit, elle ne doit rien perdre : le contenu doit survivre au passage."""
    dense = [{"id": 1, "content": "texte", "heading": "A > B"}]
    fused = rrf([dense])
    assert fused[0]["content"] == "texte"
    assert fused[0]["heading"] == "A > B"
    assert "fusion_score" in fused[0]
