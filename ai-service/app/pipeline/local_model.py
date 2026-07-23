"""Inférence locale du modèle de triage fine-tuné (ONNX) — S3-J3.

Charge l'encodeur XLM-R multi-têtes (exporté en ONNX en S3-J2) + son tokenizer, et classe un
texte en **(label, confiance)** par tête. La confiance est le max du softmax des logits.

Chargement **paresseux et résilient** : si l'artefact est absent (CI, dev sans modèle téléchargé),
`classify()` renvoie `None` — le routeur escaladera alors systématiquement au LLM. Le service
démarre donc toujours, avec ou sans modèle.
"""
import logging
from pathlib import Path

from app.config import settings

logger = logging.getLogger(__name__)

# Ordre des sorties ONNX = output_names de l'export (voir ml/finetune_xlmr.ipynb).
HEAD_ORDER = ["category", "priority", "sentiment"]
LABELS = {
    "category": ["TECHNIQUE", "FACTURATION", "COMPTE", "RECLAMATION", "DEMANDE"],
    "priority": ["LOW", "MEDIUM", "HIGH"],
    "sentiment": ["NEG", "NEU", "POS"],
}
MAX_LEN = 256

_session = None
_tokenizer = None
_load_failed = False


def _load() -> None:
    global _session, _tokenizer, _load_failed
    if _session is not None or _load_failed:
        return
    try:
        import onnxruntime as ort
        from transformers import AutoTokenizer

        model_dir = Path(settings.model_dir)
        onnx_path = model_dir / "triage_xlmr.onnx"
        tok_dir = model_dir / "triage_tokenizer"
        if not onnx_path.exists() or not tok_dir.exists():
            raise FileNotFoundError(f"artefact absent dans {model_dir}")
        _session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        _tokenizer = AutoTokenizer.from_pretrained(str(tok_dir))
        logger.info("Modele de triage ONNX charge depuis %s", model_dir)
    except Exception as exc:  # noqa: BLE001
        _load_failed = True
        logger.warning("Modele de triage indisponible (%s) - escalade LLM systematique", exc)


def _softmax(logits):
    import numpy as np

    e = np.exp(logits - logits.max())
    return e / e.sum()


def classify(text: str) -> dict | None:
    """{'category': (label, conf), 'priority': (label, conf), 'sentiment': (label, conf)} ou None."""
    _load()
    if _session is None:
        return None
    enc = _tokenizer(text, truncation=True, padding="max_length", max_length=MAX_LEN, return_tensors="np")
    feeds = {
        "input_ids": enc["input_ids"].astype("int64"),
        "attention_mask": enc["attention_mask"].astype("int64"),
    }
    outputs = _session.run(None, feeds)  # 3 tenseurs de logits [1, n_classes]
    result = {}
    for i, head in enumerate(HEAD_ORDER):
        probs = _softmax(outputs[i][0])
        idx = int(probs.argmax())
        result[head] = (LABELS[head][idx], float(probs[idx]))
    return result


def available() -> bool:
    _load()
    return _session is not None
