"""Formatage d'un vecteur en littéral pgvector (logique pure, sans modèle ni base)."""
from app.pipeline.embeddings import _to_pgvector


def test_to_pgvector_format():
    assert _to_pgvector([0.1, -0.2, 1.0]) == "[0.100000,-0.200000,1.000000]"


def test_to_pgvector_empty():
    assert _to_pgvector([]) == "[]"
