"""Routeur de confiance du pipeline de triage (S3-J3).

On simule le modèle local et le LLM (monkeypatch) pour tester la logique de routage sans artefact
ni réseau : confiant → local seul ; peu sûr → escalade ; modèle absent → tout au LLM.
"""
import asyncio

import pytest

from app.pipeline import keywords, llm_classifier, local_model, triage
from app.schemas import AnalyzeRequest, Category, Sentiment


@pytest.fixture(autouse=True)
def _stub_keywords(monkeypatch):
    # Évite de charger KeyBERT/e5 (téléchargement ~1 Go) pendant les tests du routeur.
    monkeypatch.setattr(keywords, "extract", lambda text, top_n=5: [])


def _run(text: str):
    return asyncio.run(triage.analyze(AnalyzeRequest(text=text, language="fr")))


def test_confident_local_no_escalation(monkeypatch):
    monkeypatch.setattr(local_model, "classify", lambda text: {
        "category": ("FACTURATION", 0.95), "priority": ("LOW", 0.50), "sentiment": ("NEG", 0.90)})
    res = _run("Facture incorrecte, double prélèvement")
    assert res.category == Category.FACTURATION
    assert res.sentiment == Sentiment.NEG
    assert res.escalated_to_llm is False
    assert res.model_used == "xlm-r-onnx"


def test_low_confidence_escalates_only_missing_head(monkeypatch):
    monkeypatch.setattr(local_model, "classify", lambda text: {
        "category": ("FACTURATION", 0.95), "priority": ("LOW", 0.50), "sentiment": ("NEU", 0.40)})

    async def fake_llm(text):
        return {"category": Category.TECHNIQUE, "sentiment": Sentiment.POS}

    monkeypatch.setattr(llm_classifier, "classify_llm", fake_llm)
    res = _run("Merci pour le service")
    assert res.escalated_to_llm is True
    assert res.category == Category.FACTURATION   # gardé du local (confiant)
    assert res.sentiment == Sentiment.POS         # complété par le LLM
    assert res.model_used == "hybrid"


def test_no_local_model_uses_llm(monkeypatch):
    monkeypatch.setattr(local_model, "classify", lambda text: None)

    async def fake_llm(text):
        return {"category": Category.TECHNIQUE, "sentiment": Sentiment.NEG}

    monkeypatch.setattr(llm_classifier, "classify_llm", fake_llm)
    res = _run("Erreur 500 sur la page de paiement")
    assert res.escalated_to_llm is True
    assert res.model_used == "llm"
    assert res.category == Category.TECHNIQUE
