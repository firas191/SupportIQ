"""Découpage sémantique de la base de connaissances (S5-J1).

Le chunker est testé unitairement parce que sa sortie **conditionne toute la qualité du
retrieval** : un fragment incohérent ne sera jamais rattrapé par le meilleur des modèles. Il est
volontairement sans dépendance (ni base, ni embeddings), donc testable sans infrastructure.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.kb.chunker import MAX_CHARS, chunk_document, split_sections

DOC = """# FAQ Facturation

## Remboursement

Vous pouvez demander un remboursement dans les 14 jours suivant votre achat.

Le traitement prend 5 jours ouvres.

### Cas particulier

En periode de soldes le delai double.

## Facture

La facture est disponible en PDF.
"""


def test_headings_form_a_path():
    """Le chemin des titres situe le fragment dans le document — base des citations (S5-J4)."""
    sections = split_sections(DOC)
    headings = [s.heading for s in sections]
    assert "FAQ Facturation > Remboursement" in headings
    assert "FAQ Facturation > Remboursement > Cas particulier" in headings
    # Un titre de meme niveau REMPLACE le precedent, il ne s'y ajoute pas.
    assert "FAQ Facturation > Facture" in headings


def test_sections_are_never_merged():
    """Deux sujets differents ne doivent jamais finir dans le meme fragment."""
    chunks = chunk_document(DOC)
    for chunk in chunks:
        assert "remboursement" not in chunk.content.lower() or "PDF" not in chunk.content


def test_chunks_respect_the_budget():
    long_doc = "# Titre\n\n" + "\n\n".join(f"Paragraphe numero {i}. " * 12 for i in range(40))
    chunks = chunk_document(long_doc)
    assert len(chunks) > 1
    for chunk in chunks:
        # Marge de tolerance : la fusion d'un fragment de fin de section peut depasser MAX_CHARS.
        assert len(chunk.content) <= MAX_CHARS * 1.4


def test_chunks_overlap_for_continuity():
    """Sans recouvrement, une reponse a cheval sur deux fragments n'est retrouvee par aucun."""
    long_doc = "# Titre\n\n" + "\n\n".join(
        f"Phrase autonome numero {i} avec suffisamment de texte pour remplir le budget. " * 3
        for i in range(12)
    )
    chunks = chunk_document(long_doc)
    assert len(chunks) >= 2
    # La fin du fragment n doit reapparaitre au debut du fragment n+1.
    tail = chunks[0].content[-60:].strip()
    assert any(word in chunks[1].content for word in tail.split()[:4])


def test_document_without_heading_stays_one_section():
    """Un PDF ou un .txt n'a pas de titre Markdown : tout le corps forme une section unique."""
    chunks = chunk_document("Du texte brut sans le moindre titre.")
    assert len(chunks) == 1
    assert chunks[0].heading is None


def test_empty_document_yields_nothing():
    assert chunk_document("   \n\n  ") == []


def test_embedding_text_carries_the_heading():
    """Le contexte de section est embedde avec le fragment : il ameliore le rappel."""
    chunk = chunk_document(DOC)[0]
    assert chunk.heading is not None
    assert chunk.heading in chunk.embedding_text()
    assert chunk.content in chunk.embedding_text()


def test_inline_markup_is_stripped():
    """Le balisage pollue le vecteur, le prompt et l'affichage — il ne doit pas survivre."""
    chunks = chunk_document(
        "# Titre\n\nDelai de **14 jours** avec [un lien](https://x.test) et `du_code`."
    )
    content = chunks[0].content
    assert "**" not in content
    assert "14 jours" in content
    assert "un lien" in content and "https" not in content
    assert "du_code" in content and "`" not in content


def test_headings_are_cleaned_too():
    chunks = chunk_document("# **Facturation**\n\nDu contenu suffisamment long pour tenir.")
    assert chunks[0].heading == "Facturation"


def test_plain_text_is_left_alone():
    """Ni les underscores dans les identifiants, ni les multiplications ne sont du balisage."""
    chunks = chunk_document("Le champ order_id vaut 2*3 et reste intact.")
    assert "order_id" in chunks[0].content
    assert "2*3" in chunks[0].content
