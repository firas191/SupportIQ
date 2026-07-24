"""Pipeline de triage hybride (F1) — S3-J3.

Chaîne : détection de langue → **modèle local ONNX** → **routeur de confiance** → escalade **LLM**
si incertain → **priorité par règles** (ADR-0003) → `AnalysisResult`.

Routeur de confiance (seuil `settings.confidence_threshold`, 0.80 par défaut) : pour la catégorie et
le sentiment, on garde la prédiction locale si sa confiance softmax ≥ seuil ; sinon on escalade au
LLM. Une seule escalade LLM couvre les deux têtes (coût/latence maîtrisés). La priorité n'est jamais
apprise : elle est dérivée par règles.
"""
import logging

from app.config import settings
from app.nlp.language import detect_language
from app.pipeline import keywords, llm_classifier, local_model
from app.pipeline.rules import derive_priority
from app.schemas import AnalysisResult, AnalyzeRequest, Category, Sentiment

logger = logging.getLogger(__name__)


async def analyze(req: AnalyzeRequest) -> AnalysisResult:
    text = req.text
    language = req.language or detect_language(text)
    threshold = settings.confidence_threshold

    local = local_model.classify(text)  # None si modèle absent
    category: Category | None = None
    sentiment: Sentiment | None = None
    local_confidences: list[float] = []

    if local is not None:
        cat_label, cat_conf = local["category"]
        sen_label, sen_conf = local["sentiment"]
        if cat_conf >= threshold:
            category = Category(cat_label)
            local_confidences.append(cat_conf)
        if sen_conf >= threshold:
            sentiment = Sentiment(sen_label)
            local_confidences.append(sen_conf)

    # Escalade LLM si une tête manque de confiance (ou modèle local indisponible).
    escalated = category is None or sentiment is None
    if escalated:
        llm = await llm_classifier.classify_llm(text)
        if llm is not None:
            category = category or llm["category"]
            sentiment = sentiment or llm["sentiment"]

    # Filets de sécurité : si tout a échoué, retomber sur le local (même peu sûr) puis un défaut neutre.
    if category is None:
        category = Category(local["category"][0]) if local else Category.DEMANDE
    if sentiment is None:
        sentiment = Sentiment(local["sentiment"][0]) if local else Sentiment.NEU

    priority = derive_priority(text, category, sentiment)
    confidence = min(local_confidences) if local_confidences else 0.5
    if escalated:
        model_used = "hybrid" if local is not None else "llm"
    else:
        model_used = "xlm-r-onnx"

    return AnalysisResult(
        priority=priority,
        category=category,
        sentiment=sentiment,
        keywords=keywords.extract(text),  # KeyBERT (S3-J4)
        confidence=round(confidence, 3),
        language=language,
        model_used=model_used,
        escalated_to_llm=escalated,
    )
