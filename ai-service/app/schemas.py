from enum import Enum

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
