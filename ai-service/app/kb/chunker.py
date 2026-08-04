"""Découpage sémantique des documents de la base de connaissances (S5-J1).

**Pourquoi ce n'est pas un simple `text[i:i+512]`.** La qualité d'un RAG dépend beaucoup moins de
la taille des fragments que de leur **cohérence**. Une fenêtre de taille fixe coupe au milieu d'une
procédure : le fragment retrouvé contient « ... puis cliquez sur » et le suivant « Confirmer. »
Aucun des deux ne répond à la question, et le modèle génère une réponse tronquée en toute confiance.

La stratégie retenue suit la structure du document, du plus fort au plus faible séparateur :

1. **Les titres Markdown** délimitent les unités de sens. Une section « Comment obtenir un
   remboursement » est un tout : on ne la mélange jamais avec la section voisine.
2. **Les paragraphes** à l'intérieur d'une section. On les empile tant qu'on tient dans le budget,
   sans jamais en couper un.
3. **Les phrases**, uniquement quand un paragraphe dépasse à lui seul le budget.

Deux réglages qui comptent :

- **Recouvrement** (`OVERLAP_CHARS`) : la dernière phrase d'un fragment ouvre le suivant. Sans lui,
  une réponse à cheval sur deux fragments n'est retrouvée par aucun des deux.
- **Taille plancher** (`MIN_CHARS`) : un fragment de deux lignes produit un vecteur dominé par le
  bruit et remonte à tort sur des requêtes sans rapport. Les fragments trop courts sont fusionnés
  avec le suivant.

Le chemin des titres est conservé (`heading`) : il sert à citer la source à l'écran en S5-J4, et il
est **inclus dans le texte embeddé** — « Facturation > Remboursement » ajoute un contexte que le
fragment seul n'a pas toujours.

Module volontairement **sans dépendance** (ni base, ni modèle) : il est ainsi testable unitairement,
ce qui est indispensable pour une brique dont la sortie conditionne toute la qualité du retrieval.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

MAX_CHARS = 900          # ~220 tokens : confortable pour e5 (512 max) même en français
MIN_CHARS = 220          # en dessous, le vecteur porte trop peu de signal
OVERLAP_CHARS = 140      # une phrase de continuité entre deux fragments

_HEADING = re.compile(r"^(#{1,4})\s+(.*\S)\s*$")

# Balisage inline Markdown. On le retire APRES l'extraction des titres : les `#` de debut de ligne
# portent la structure, les `**` et backticks ne portent que de la mise en forme.
_BOLD = re.compile(r"\*\*(.+?)\*\*|__(.+?)__", re.S)
_ITALIC = re.compile(r"(?<![\w*])\*(?!\s)([^*\n]+?)(?<!\s)\*(?![\w*])")
_CODE = re.compile(r"`([^`\n]+)`")
_LINK = re.compile(r"\[([^\]]+)\]\([^)]*\)")
_QUOTE = re.compile(r"^>\s?", re.MULTILINE)
_PARAGRAPH_SPLIT = re.compile(r"\n\s*\n")
# Fin de phrase : ponctuation forte suivie d'un espace puis d'une majuscule ou d'un chiffre.
# Le lookbehind évite de couper sur « M. Dupont » ou « ex. : ».
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+(?=[A-ZÀ-ÝÉÈ0-9])")


@dataclass(frozen=True)
class Chunk:
    """Un fragment prêt à être embeddé et stocké."""

    index: int
    heading: str | None
    content: str

    def embedding_text(self) -> str:
        """Texte réellement envoyé au modèle : le titre de section donne du contexte au fragment."""
        return f"{self.heading}\n{self.content}" if self.heading else self.content


@dataclass(frozen=True)
class Section:
    heading: str | None
    body: str


def strip_markup(text: str) -> str:
    """Retire le balisage de mise en forme, garde le texte.

    Trois raisons, par ordre d'importance :

    1. **Le vecteur.** Les `**` deviennent des tokens a part entiere pour le modele d'embeddings.
       Ils diluent le signal du fragment sans rien apporter : deux passages identiques, l'un en gras
       l'autre non, ne devraient pas avoir des vecteurs differents.

    2. **Le prompt.** Ces fragments seront injectes tels quels dans le contexte de l'agent
       Resolution (S5-J3). Le modele imiterait alors le balisage dans sa reponse, alors que le
       brouillon part dans un e-mail en texte.

    3. **L'affichage.** « dans les **14 jours** » se lit mal dans un resultat de recherche.

    Les titres, eux, sont extraits **avant** cet appel : leur `#` porte la structure du document,
    pas de la mise en forme.
    """
    text = _LINK.sub(r"\1", text)      # [texte](url) -> texte : l'URL n'aide pas la similarite
    text = _BOLD.sub(lambda m: m.group(1) or m.group(2), text)
    text = _ITALIC.sub(r"\1", text)
    text = _CODE.sub(r"\1", text)
    text = _QUOTE.sub("", text)
    return text


def split_sections(text: str) -> list[Section]:
    """Découpe un Markdown en sections, en conservant le **chemin** des titres.

    « ## Facturation » puis « ### Remboursement » donne le chemin « Facturation > Remboursement ».
    Un fragment isolé reste ainsi rattachable à sa place dans le document.
    """
    sections: list[Section] = []
    path: list[str] = []
    buffer: list[str] = []

    def flush() -> None:
        body = strip_markup("\n".join(buffer)).strip()
        if body:
            sections.append(Section(" > ".join(path) if path else None, body))
        buffer.clear()

    for line in text.splitlines():
        match = _HEADING.match(line)
        if match:
            flush()
            level = len(match.group(1))
            # On tronque le chemin au niveau courant avant d'y ajouter le nouveau titre :
            # un « ## B » qui suit « ### A » remplace A, il ne s'y ajoute pas.
            path = path[: level - 1]
            path.append(strip_markup(match.group(2)).strip())
        else:
            buffer.append(line)

    flush()
    # Aucun titre dans le document (PDF, texte brut) : tout le corps forme une section unique.
    cleaned = strip_markup(text).strip()
    return sections or ([Section(None, cleaned)] if cleaned else [])


def _split_long_paragraph(paragraph: str) -> list[str]:
    """Coupe un paragraphe trop long sur des fins de phrase, jamais au milieu d'un mot."""
    sentences = _SENTENCE_END.split(paragraph)
    parts: list[str] = []
    current = ""
    for sentence in sentences:
        candidate = f"{current} {sentence}".strip()
        if current and len(candidate) > MAX_CHARS:
            parts.append(current)
            current = sentence
        else:
            current = candidate
    if current:
        parts.append(current)

    # Filet de sécurité : une « phrase » unique de 3 000 caractères (tableau, liste sans
    # ponctuation) ne doit pas produire un fragment hors budget.
    out: list[str] = []
    for part in parts:
        while len(part) > MAX_CHARS:
            cut = part.rfind(" ", 0, MAX_CHARS) or MAX_CHARS
            out.append(part[:cut].strip())
            part = part[cut:].strip()
        if part:
            out.append(part)
    return out


def _tail(text: str) -> str:
    """Dernière phrase du fragment, tronquée au budget de recouvrement."""
    sentences = _SENTENCE_END.split(text)
    tail = sentences[-1] if sentences else text
    return tail[-OVERLAP_CHARS:].strip()


def chunk_document(text: str) -> list[Chunk]:
    """Transforme un document en fragments cohérents, numérotés dans l'ordre de lecture."""
    chunks: list[Chunk] = []

    for section in split_sections(text):
        pieces: list[str] = []
        for paragraph in _PARAGRAPH_SPLIT.split(section.body):
            paragraph = paragraph.strip()
            if not paragraph:
                continue
            pieces.extend(
                _split_long_paragraph(paragraph) if len(paragraph) > MAX_CHARS else [paragraph]
            )

        current = ""
        for piece in pieces:
            candidate = f"{current}\n\n{piece}".strip() if current else piece
            if current and len(candidate) > MAX_CHARS:
                chunks.append(Chunk(len(chunks), section.heading, current))
                # Recouvrement : le nouveau fragment reprend la fin du précédent.
                overlap = _tail(current)
                current = f"{overlap}\n\n{piece}".strip() if overlap else piece
            else:
                current = candidate

        if current:
            # Fragment de fin de section trop court : on le rattache au précédent **de la même
            # section**, jamais d'une section voisine (ce serait mélanger deux sujets).
            if (
                len(current) < MIN_CHARS
                and chunks
                and chunks[-1].heading == section.heading
                and len(chunks[-1].content) + len(current) <= MAX_CHARS + MIN_CHARS
            ):
                merged = f"{chunks[-1].content}\n\n{current}"
                chunks[-1] = Chunk(chunks[-1].index, section.heading, merged)
            else:
                chunks.append(Chunk(len(chunks), section.heading, current))

    return chunks
