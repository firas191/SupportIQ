"""Pipeline de triage hybride (F1).

Semaine 3 :
  J1  baselines (TF-IDF + LinearSVC, zero-shot LLM) sur le test set gelé
  J2  fine-tuning XLM-RoBERTa multi-têtes, export ONNX
  J3  routeur de confiance : local si conf >= seuil, sinon escalade LLM
"""
from app.schemas import AnalyzeRequest, AnalysisResult


async def analyze(req: AnalyzeRequest) -> AnalysisResult:
    raise NotImplementedError("Implémenté en semaine 3 — voir planning J1-J3")
