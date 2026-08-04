"""Lecture des documents de la base de connaissances (S5-J1).

Trois formats couverts : **Markdown**, **texte brut** et **PDF**. Le choix suit ce qu'une équipe
support possède réellement — une FAQ rédigée en Markdown dans un wiki, ou des procédures exportées
en PDF. Les formats bureautiques (docx) et l'OCR arrivent en S7 avec la couche d'ingestion
universelle (rapport §5.4) : les ajouter ici serait travailler en avance sur le planning.

Le PDF passe par **PyMuPDF** plutôt que pypdf : l'extraction respecte l'ordre de lecture des blocs,
là où pypdf restitue souvent le texte dans l'ordre du flux interne — ce qui mélange les colonnes et
détruit précisément la cohérence que le découpage cherche à préserver.

Import **paresseux et résilient** : si PyMuPDF manque, seuls les PDF échouent, avec un message
explicite. Le service démarre et le reste de la KB fonctionne.
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass

logger = logging.getLogger(__name__)

MARKDOWN_EXT = {".md", ".markdown"}
TEXT_EXT = {".txt"}
PDF_EXT = {".pdf"}
SUPPORTED_EXT = MARKDOWN_EXT | TEXT_EXT | PDF_EXT

_H1 = re.compile(r"^#\s+(.*\S)\s*$", re.MULTILINE)


class UnsupportedDocument(Exception):
    """Format non pris en charge, ou fichier illisible."""


@dataclass(frozen=True)
class LoadedDocument:
    title: str
    text: str


def extension_of(filename: str) -> str:
    dot = filename.rfind(".")
    return filename[dot:].lower() if dot >= 0 else ""


def load(filename: str, data: bytes) -> LoadedDocument:
    """Extrait le texte et un titre lisible depuis les octets d'un fichier."""
    ext = extension_of(filename)
    if ext not in SUPPORTED_EXT:
        raise UnsupportedDocument(
            f"Format {ext or 'inconnu'} non pris en charge (attendus : "
            f"{', '.join(sorted(SUPPORTED_EXT))})"
        )

    text = _read_pdf(data) if ext in PDF_EXT else _read_text(data)
    text = _normalise(text)
    if not text:
        raise UnsupportedDocument("Le document ne contient aucun texte exploitable")

    return LoadedDocument(title=_title_of(filename, text), text=text)


def _read_text(data: bytes) -> str:
    """Décodage tolérant : UTF-8 d'abord (avec ou sans BOM), Latin-1 en repli.

    Latin-1 ne peut pas échouer — tout octet y est valide. C'est le repli qui garantit qu'un
    document mal encodé produit du texte légèrement fautif plutôt qu'une erreur 400.
    """
    for encoding in ("utf-8-sig", "utf-8", "cp1252", "latin-1"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("latin-1", errors="replace")


def _read_pdf(data: bytes) -> str:
    try:
        import fitz  # PyMuPDF
    except ImportError as exc:  # pragma: no cover - dépend de l'environnement
        raise UnsupportedDocument(
            "Lecture PDF indisponible sur ce serveur (PyMuPDF absent)"
        ) from exc

    try:
        with fitz.open(stream=data, filetype="pdf") as doc:
            # `sort=True` : blocs restitués dans l'ordre de lecture visuel, pas dans l'ordre du
            # flux interne du PDF. Sans cela, un document sur deux colonnes sort entrelacé.
            pages = [page.get_text("text", sort=True) for page in doc]
    except Exception as exc:
        raise UnsupportedDocument(f"PDF illisible : {exc}") from exc

    return "\n\n".join(p.strip() for p in pages if p.strip())


def _normalise(text: str) -> str:
    """Nettoyage minimal : retours de ligne uniformes, césures PDF recollées, blancs réduits."""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    # Césure de fin de ligne propre aux PDF : « rembour-\nsement » → « remboursement ».
    text = re.sub(r"(\w)-\n(\w)", r"\1\2", text)
    # Trois sauts de ligne ou plus = une seule séparation de paragraphe.
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = re.sub(r"[ \t]+", " ", text)
    return text.strip()


def _title_of(filename: str, text: str) -> str:
    """Titre H1 du document s'il existe, sinon le nom de fichier rendu lisible."""
    match = _H1.search(text)
    if match:
        return match.group(1)[:300]
    stem = filename.rsplit("/", 1)[-1]
    stem = stem[: stem.rfind(".")] if "." in stem else stem
    return stem.replace("_", " ").replace("-", " ").strip()[:300] or filename[:300]
