"""Lecture des documents de la base de connaissances (S5-J1).

On teste ici ce qui casse en production : un encodage exotique, un format refusé, et le titre
retenu pour un document sans titre Markdown.
"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.kb.loader import UnsupportedDocument, load


def test_markdown_title_comes_from_h1():
    doc = load("faq.md", b"# Facturation et paiements\n\nDu contenu.")
    assert doc.title == "Facturation et paiements"


def test_title_falls_back_to_filename():
    """Un .txt n'a pas de H1 : le nom de fichier est rendu lisible."""
    doc = load("procedure-interne_v2.txt", b"Contenu sans titre")
    assert doc.title == "procedure interne v2"


def test_latin1_document_is_not_rejected():
    """Un encodage non UTF-8 doit produire du texte, jamais une erreur : perdre un document
    parce qu'il vient d'un vieil export serait une regression fonctionnelle."""
    doc = load("faq.txt", "Délai de rétractation".encode("cp1252"))
    assert "tractation" in doc.text


def test_pdf_hyphenation_is_repaired():
    """Les PDF coupent les mots en fin de ligne ; sans recollage, « rembour-sement » ne serait
    trouve par aucune recherche."""
    from app.kb.loader import normalise as _normalise

    assert "remboursement" in _normalise("un rembour-\nsement complet")


def test_unsupported_format_is_rejected():
    with pytest.raises(UnsupportedDocument):
        load("capture.png", b"\x89PNG")


def test_empty_document_is_rejected():
    with pytest.raises(UnsupportedDocument):
        load("vide.md", b"   \n\n  ")
