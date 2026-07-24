#!/usr/bin/env python3
"""Harness d'évaluation du pipeline hybride (S3-J5).

À lancer **dans le conteneur ai-service** (où vivent le modèle ONNX, litellm et les clés) :

    docker compose exec ai-service python /eval/evaluate_pipeline.py

Compare, sur le **test set gelé**, trois régimes par tête (catégorie, sentiment) :
  - **local seul** (XLM-R ONNX),
  - **LLM seul** (zero-shot),
  - **hybride** (routeur : local si confiance ≥ seuil, sinon LLM).

Puis **balaye le seuil de confiance** (escalade vs macro-F1) pour calibrer l'ADR-0004. Astuce coût :
on calcule local + LLM **une seule fois** pour les 300 tickets, puis chaque seuil se simule à partir
de ces prédictions (le routeur est déterministe une fois qu'on a (label, confiance) local et la
prédiction LLM). Écrit un rapport Markdown commité dans eval/results/.
"""
import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, "/srv")  # importer le pipeline du service (app.*)

from app.pipeline import llm_classifier, local_model  # noqa: E402
from app.schemas import Category, Sentiment  # noqa: E402

TEST = Path("/eval/datasets/test.jsonl")
OUT = Path("/eval/results/pipeline_eval_s3j5.md")
HEADS = ["category", "sentiment"]
THRESHOLDS = [0.50, 0.60, 0.70, 0.80, 0.90]
LABELS = {"category": [c.value for c in Category], "sentiment": [s.value for s in Sentiment]}


def load() -> list[dict]:
    return [json.loads(line) for line in TEST.read_text(encoding="utf-8").splitlines() if line.strip()]


def macro_f1(gold: list[str], pred: list[str], labels: list[str]) -> float:
    """Macro-F1 en stdlib (le conteneur n'a pas scikit-learn)."""
    scores = []
    for lab in labels:
        tp = sum(1 for g, p in zip(gold, pred) if g == lab and p == lab)
        fp = sum(1 for g, p in zip(gold, pred) if g != lab and p == lab)
        fn = sum(1 for g, p in zip(gold, pred) if g == lab and p != lab)
        prec = tp / (tp + fp) if (tp + fp) else 0.0
        rec = tp / (tp + fn) if (tp + fn) else 0.0
        scores.append(2 * prec * rec / (prec + rec) if (prec + rec) else 0.0)
    return sum(scores) / len(scores)


async def main() -> int:
    rows = load()
    gold = {h: [r[h] for r in rows] for h in HEADS}
    print(f"test={len(rows)} tickets", file=sys.stderr)

    # 1. Local (ONNX) : (label, confiance) par tête, une fois.
    local = {h: [] for h in HEADS}
    local_conf = {h: [] for h in HEADS}
    for r in rows:
        res = local_model.classify(r["text"])
        for h in HEADS:
            lab, conf = res[h] if res else (LABELS[h][0], 0.0)
            local[h].append(lab)
            local_conf[h].append(conf)
    print("local (ONNX) calculé", file=sys.stderr)

    # 2. LLM zero-shot, une fois pour tous (réutilisé par l'hybride).
    llm = {h: [] for h in HEADS}
    for i, r in enumerate(rows):
        pred = await llm_classifier.classify_llm(r["text"])
        for h in HEADS:
            llm[h].append(pred[h].value if pred else LABELS[h][0])
        if (i + 1) % 20 == 0:
            print(f"  LLM {i + 1}/{len(rows)}", file=sys.stderr)

    def f1_of(pred: dict) -> dict:
        return {h: macro_f1(gold[h], pred[h], LABELS[h]) for h in HEADS}

    f1_local = f1_of(local)
    f1_llm = f1_of(llm)

    # 3. Balayage du seuil hybride (simulé à partir de local + llm déjà calculés).
    sweep = []
    for t in THRESHOLDS:
        hyb = {h: [] for h in HEADS}
        escalated = 0
        for i in range(len(rows)):
            row_escalated = False
            for h in HEADS:
                if local_conf[h][i] >= t:
                    hyb[h].append(local[h][i])
                else:
                    hyb[h].append(llm[h][i])
                    row_escalated = True
            escalated += 1 if row_escalated else 0
        sweep.append({"threshold": t, "escalation_rate": escalated / len(rows), "f1": f1_of(hyb)})

    _write_report(rows, f1_local, f1_llm, sweep)
    print(f"\nRapport écrit : {OUT}", file=sys.stderr)
    for h in HEADS:
        print(f"  {h:9} local={f1_local[h]:.2f}  llm={f1_llm[h]:.2f}", file=sys.stderr)
    return 0


def _write_report(rows, f1_local, f1_llm, sweep) -> None:
    md = ["# Évaluation du pipeline hybride — test set gelé (S3-J5)\n",
          f"- Test : **{len(rows)} tickets** gelés (`eval/datasets/test.jsonl`).",
          "- Métrique : **macro-F1** par tête. Priorité exclue (dérivée par règles, ADR-0003).\n",
          "## Régimes (macro-F1)\n",
          "| Tête | Local seul (ONNX) | LLM seul (0-shot) |",
          "|---|---|---|"]
    for h in HEADS:
        md.append(f"| {h} | {f1_local[h]:.2f} | {f1_llm[h]:.2f} |")
    md.append("\n## Balayage du seuil de confiance (hybride)\n")
    md.append("| Seuil | Taux d'escalade | F1 catégorie | F1 sentiment |")
    md.append("|---|---|---|---|")
    for s in sweep:
        md.append(f"| {s['threshold']:.2f} | {s['escalation_rate']:.0%} | "
                  f"{s['f1']['category']:.2f} | {s['f1']['sentiment']:.2f} |")
    md.append("\n**Lecture (ADR-0004)** : chaque escalade coûte un appel LLM (latence + tokens). "
              "On cherche le seuil le plus **bas** qui garde un F1 proche du meilleur — c'est le "
              "meilleur compromis coût/qualité. Voir `docs/adr/0004-seuil-routeur-confiance.md`.")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(md), encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
