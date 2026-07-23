#!/usr/bin/env python3
"""Baselines de référence sur le test set gelé (S3-J1).

Trois références, évaluées sur `eval/datasets/test.jsonl` (jamais vu à l'entraînement) :
  1. **Majorité**   — prédit toujours la classe la plus fréquente du train. Plancher trivial :
     tout modèle utile doit le battre (surtout en macro-F1, insensible au déséquilibre).
  2. **TF-IDF + LinearSVC** — baseline ML classique, rapide, 0 $. C'est le vrai point de
     comparaison honnête du fine-tuning XLM-RoBERTa (S3-J2).
  3. **LLM zero-shot** — le modèle classe sans exemple ni fine-tuning (passerelle stdlib du
     générateur, multi-comptes). Borne haute "sans entraînement", mais coûteuse et lente.

Sortie : tableau par tête (catégorie/priorité/sentiment) avec F1 par classe + macro-F1, matrice
de confusion, exemples d'erreurs. Écrit un rapport Markdown commité dans eval/results/.

Prérequis ML : pip install -r eval/requirements-ml.txt   (scikit-learn ; wheels dispo, pas de Rust)
Usage        : python eval/baselines.py            # avec baseline LLM (~1 min de plus)
               python eval/baselines.py --no-llm   # ML + majorité seulement (hors ligne)
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "ai-service"))     # pour importer app.nlp.language
sys.path.insert(0, str(Path(__file__).resolve().parent))  # pour importer generate_dataset

from app.nlp.language import detect_language  # noqa: E402

TASKS = ["category", "priority", "sentiment"]
LABELS = {
    "category": ["TECHNIQUE", "FACTURATION", "COMPTE", "RECLAMATION", "DEMANDE"],
    "priority": ["LOW", "MEDIUM", "HIGH"],
    "sentiment": ["NEG", "NEU", "POS"],
}


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


# --- Métriques ---------------------------------------------------------------

def scores(gold: list[str], pred: list[str], labels: list[str]) -> dict:
    from sklearn.metrics import classification_report, confusion_matrix, f1_score
    report = classification_report(gold, pred, labels=labels, output_dict=True, zero_division=0)
    macro = f1_score(gold, pred, labels=labels, average="macro", zero_division=0)
    acc = sum(g == p for g, p in zip(gold, pred)) / len(gold)
    cm = confusion_matrix(gold, pred, labels=labels)
    return {"report": report, "macro_f1": macro, "accuracy": acc, "confusion": cm.tolist()}


def majority_pred(train: list[dict], test: list[dict], task: str) -> list[str]:
    top = Counter(r[task] for r in train).most_common(1)[0][0]
    return [top] * len(test)


def tfidf_svc_pred(train: list[dict], test: list[dict], task: str) -> list[str]:
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.pipeline import make_pipeline
    from sklearn.svm import LinearSVC
    pipe = make_pipeline(
        TfidfVectorizer(ngram_range=(1, 2), min_df=2, sublinear_tf=True, strip_accents=None),
        LinearSVC(C=1.0),
    )
    pipe.fit([r["text"] for r in train], [r[task] for r in train])
    return list(pipe.predict([r["text"] for r in test]))


def llm_preds(test: list[dict], batch: int) -> dict[str, list[str]]:
    """Classe le test à l'aveugle via la passerelle LLM du générateur (zero-shot)."""
    import generate_dataset as gd
    gd.load_env(REPO_ROOT / ".env")
    providers = gd.build_providers(None)
    out = {task: [] for task in TASKS}
    texts = [r["text"] for r in test]
    for start in range(0, len(texts), batch):
        chunk = texts[start : start + batch]
        preds = gd.classify_batch(chunk, providers)
        for pred in preds:
            for task in TASKS:
                out[task].append((pred or {}).get(task, "UNK"))
        print(f"  LLM {min(start + batch, len(texts))}/{len(texts)}", file=sys.stderr)
    return out


# --- Rapport -----------------------------------------------------------------

def per_class_table(gold: list[str], preds_by_model: dict[str, list[str]], labels: list[str]) -> str:
    from sklearn.metrics import f1_score
    header = "| Classe | " + " | ".join(f"F1 {m}" for m in preds_by_model) + " | Support |"
    sep = "|" + "---|" * (len(preds_by_model) + 2)
    lines = [header, sep]
    support = Counter(gold)
    for label in labels:
        cells = []
        for pred in preds_by_model.values():
            f1 = f1_score(gold, pred, labels=[label], average="macro", zero_division=0)
            cells.append(f"{f1:.2f}")
        lines.append(f"| {label} | " + " | ".join(cells) + f" | {support.get(label, 0)} |")
    return "\n".join(lines)


def confusion_md(cm: list[list[int]], labels: list[str]) -> str:
    head = "| gold ↓ / pred → | " + " | ".join(labels) + " |"
    sep = "|" + "---|" * (len(labels) + 1)
    rows = [head, sep]
    for label, row in zip(labels, cm):
        rows.append(f"| **{label}** | " + " | ".join(str(v) for v in row) + " |")
    return "\n".join(rows)


def error_examples(test: list[dict], gold: list[str], pred: list[str], n: int = 6) -> str:
    lines = ["| gold | prédit | texte |", "|---|---|---|"]
    shown = 0
    for row, g, p in zip(test, gold, pred):
        if g != p and shown < n:
            snippet = row["text"].replace("\n", " ")[:90]
            lines.append(f"| {g} | {p} | {snippet} |")
            shown += 1
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Baselines sur le test set gelé (S3-J1).")
    parser.add_argument("--data-dir", default=str(REPO_ROOT / "eval" / "datasets"))
    parser.add_argument("--out", default=str(REPO_ROOT / "eval" / "results" / "baseline_s3j1.md"))
    parser.add_argument("--no-llm", action="store_true", help="sauter la baseline LLM (hors ligne)")
    parser.add_argument("--llm-batch", type=int, default=10)
    args = parser.parse_args()

    data_dir = Path(args.data_dir)
    train = load_jsonl(data_dir / "train.jsonl")
    test = load_jsonl(data_dir / "test.jsonl")
    print(f"train={len(train)}  test={len(test)}", file=sys.stderr)

    # Détection de langue (heuristique, sans entraînement).
    lang_acc = sum(detect_language(r["text"]) == r["language"] for r in test) / len(test)

    llm = None if args.no_llm else llm_preds(test, args.llm_batch)

    md = ["# Baselines de référence — test set gelé (S3-J1)\n",
          f"- Jeu de test : **{len(test)} tickets** gelés (`eval/datasets/test.jsonl`), jamais vus.",
          f"- Jeu d'entraînement : {len(train)} tickets synthétiques.",
          f"- **Détection de langue FR/EN (heuristique)** : exactitude **{lang_acc:.1%}** sur le test.\n",
          "> Métrique principale : **macro-F1** (moyenne des F1 par classe, insensible au déséquilibre).\n",
          "## Synthèse (macro-F1 par tête)\n"]

    summary = {task: {} for task in TASKS}
    details = []
    for task in TASKS:
        labels = LABELS[task]
        gold = [r[task] for r in test]
        preds = {"Majorité": majority_pred(train, test, task),
                 "TF-IDF+SVC": tfidf_svc_pred(train, test, task)}
        if llm is not None:
            preds["LLM 0-shot"] = llm[task]
        for name, pred in preds.items():
            summary[task][name] = scores(gold, pred, labels)["macro_f1"]

        details.append(f"### Tête : {task}\n")
        details.append(per_class_table(gold, preds, labels) + "\n")
        best = "TF-IDF+SVC"
        details.append(f"**Matrice de confusion — {best}** (gold en lignes) :\n")
        details.append(confusion_md(scores(gold, preds[best], labels)["confusion"], labels) + "\n")
        details.append(f"**Exemples d'erreurs — {best}** :\n")
        details.append(error_examples(test, gold, preds[best]) + "\n")

    # Tableau de synthèse
    models = list(next(iter(summary.values())).keys())
    md.append("| Tête | " + " | ".join(models) + " |")
    md.append("|" + "---|" * (len(models) + 1))
    for task in TASKS:
        md.append(f"| {task} | " + " | ".join(f"{summary[task][m]:.2f}" for m in models) + " |")
    md.append("")
    md.extend(details)

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(md), encoding="utf-8")
    print(f"\nRapport écrit : {out_path}", file=sys.stderr)
    print("\n".join(f"  {task:9} macro-F1: "
                    + ", ".join(f"{m}={summary[task][m]:.2f}" for m in models) for task in TASKS),
          file=sys.stderr)
    print(f"  langue FR/EN exactitude: {lang_acc:.1%}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
