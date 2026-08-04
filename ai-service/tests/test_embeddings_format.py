"""Formatage d'un vecteur en littéral pgvector (logique pure, sans modèle ni base).

`to_pgvector` est **public** depuis S5-J1 : la base de connaissances réutilise exactement le même
encodage que les embeddings de tickets. Un seul encodeur pour les deux tables évite qu'une
divergence de format ne produise des distances silencieusement fausses.
"""
from app.pipeline.embeddings import to_pgvector


def test_to_pgvector_format():
    assert to_pgvector([0.1, -0.2, 1.0]) == "[0.100000,-0.200000,1.000000]"


def test_to_pgvector_empty():
    assert to_pgvector([]) == "[]"
