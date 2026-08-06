from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field


class Priority(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class Category(str, Enum):
    TECHNIQUE = "TECHNIQUE"
    FACTURATION = "FACTURATION"
    COMPTE = "COMPTE"
    RECLAMATION = "RECLAMATION"
    DEMANDE = "DEMANDE"


class Sentiment(str, Enum):
    NEG = "NEG"
    NEU = "NEU"
    POS = "POS"


class AnalyzeRequest(BaseModel):
    ticket_id: int | None = None
    text: str = Field(min_length=1)
    language: str | None = None  # "fr" | "en" — détectée si absente


class AnalysisResult(BaseModel):
    """Contrat de sortie strict — toute réponse LLM est validée contre ce schéma."""
    priority: Priority
    category: Category
    sentiment: Sentiment
    keywords: list[str] = []
    confidence: float = Field(ge=0, le=1)
    language: str
    model_used: str
    escalated_to_llm: bool = False


class SimilarRequest(BaseModel):
    """Recherche de tickets similaires : par id (embedding déjà stocké) ou par texte libre."""
    ticket_id: int | None = None
    text: str | None = None
    k: int = Field(default=5, ge=1, le=50)


class SimilarTicket(BaseModel):
    ticket_id: int
    subject: str | None = None
    category: str | None = None
    similarity: float           # cosinus (1 = identique)
    is_duplicate: bool          # même catégorie + cosinus ≥ seuil de doublon


# --- Base de connaissances (S5-J1) ------------------------------------------


class KbDocument(BaseModel):
    """Un document indexé, vu depuis l'écran d'administration (agrégat de ses fragments)."""

    source: str
    title: str
    chunks: int
    indexed: int
    updated_at: str | None = None


class KbIngestResult(BaseModel):
    source: str
    title: str
    chunks: int
    indexed: int
    characters: int


class KbSearchRequest(BaseModel):
    question: str = Field(min_length=2, max_length=1000)
    k: int = Field(default=5, ge=1, le=20)
    # « vector » = comportement du S5-J1 (vecteurs seuls), conserve comme point de comparaison
    # chiffre ; « hybrid » = BM25 + vecteurs, fusion RRF, reranking cross-encodeur (S5-J2).
    mode: Literal["vector", "hybrid"] = "hybrid"


class KbChunk(BaseModel):
    """Fragment retrouvé. `heading` et `source` permettent de citer précisément (S5-J3/J4)."""

    id: int
    title: str
    source: str
    chunk_index: int
    heading: str | None = None
    content: str
    similarity: float


class KbReindexResult(BaseModel):
    processed: int
    failed: int


# --- Agent Resolution (S5-J3) -----------------------------------------------


class ResolutionRequest(BaseModel):
    """Contrat du rapport §6 : POST /agents/resolution {ticket_id} -> DraftResponse."""

    ticket_id: int
    # Ton configurable (rapport §5.2). Deux registres suffisent : le formel couvre le support
    # standard, l'empathique les reclamations et les clients mecontents.
    tone: Literal["formal", "empathetic"] = "formal"


class Citation(BaseModel):
    """Source d'une affirmation du brouillon.

    `chunk_id` + `source` + `heading` permettront le surlignage du passage exact dans l'ecran de la
    base de connaissances (S5-J4) ; `excerpt` evite a l'agent de changer d'ecran pour verifier.
    """

    marker: int
    chunk_id: int | None = None
    source: str | None = None
    heading: str | None = None
    excerpt: str = ""


class DraftResponse(BaseModel):
    """Brouillon propose. **Jamais envoye automatiquement** : un humain valide (rapport §5.2)."""

    draft_id: int | None = None
    ticket_id: int
    content: str
    citations: list[Citation] = []
    tone: str = "formal"
    # Vrai quand l'auto-verification n'a pas converge : l'interface doit avertir avant lecture.
    low_confidence: bool = False
    # Le modele a explicitement reconnu que la documentation ne couvre pas la demande. C'est un
    # **resultat correct**, pas un echec : l'interface affiche « rien a proposer » et non une
    # alerte. Distinguer les deux evite les fausses alarmes, qui apprennent a ignorer les vraies.
    abstained: bool = False
    issues: list[str] = []
    attempts: int = 0
    passages_used: int = 0
