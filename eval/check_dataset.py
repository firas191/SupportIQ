#!/usr/bin/env python3
"""Garde-fou du test set gelé, exécuté en CI (S3-J5).

Le test set (`eval/datasets/test.jsonl`) est la **référence** de toutes les évals. Il ne doit jamais
être corrompu ni dériver silencieusement. Ce script (stdlib pure, aucune dépendance) valide sa
structure et ses labels et échoue (exit 1) au moindre problème.

Note : l'éval F1 *complète* en CI (local/hybride) nécessiterait le modèle ONNX versionné (registre
de modèles) — reporté. Ici on garantit au moins l'intégrité du jeu de référence.

    python eval/check_dataset.py
"""
import json
import sys
from collections import Counter
from pathlib import Path

TEST = Path(__file__).resolve().parent / "datasets" / "test.jsonl"
CATEGORIES = {"TECHNIQUE", "FACTURATION", "COMPTE", "RECLAMATION", "DEMANDE"}
PRIORITIES = {"LOW", "MEDIUM", "HIGH"}
SENTIMENTS = {"NEG", "NEU", "POS"}
LANGUAGES = {"fr", "en"}
REQUIRED = {"text", "category", "priority", "sentiment", "language"}
MIN_ROWS = 50  # le test gelé doit exister et être substantiel


def main() -> int:
    if not TEST.exists():
        print(f"ERREUR : {TEST} introuvable (le test set gelé doit être versionné).")
        return 1

    rows, errors = [], []
    for n, line in enumerate(TEST.read_text(encoding="utf-8").splitlines(), start=1):
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            errors.append(f"ligne {n}: JSON invalide ({exc})")
            continue
        missing = REQUIRED - row.keys()
        if missing:
            errors.append(f"ligne {n}: champs manquants {missing}")
            continue
        if not str(row["text"]).strip():
            errors.append(f"ligne {n}: text vide")
        if row["category"] not in CATEGORIES:
            errors.append(f"ligne {n}: category invalide '{row['category']}'")
        if row["priority"] not in PRIORITIES:
            errors.append(f"ligne {n}: priority invalide '{row['priority']}'")
        if row["sentiment"] not in SENTIMENTS:
            errors.append(f"ligne {n}: sentiment invalide '{row['sentiment']}'")
        if row["language"] not in LANGUAGES:
            errors.append(f"ligne {n}: language invalide '{row['language']}'")
        rows.append(row)

    if len(rows) < MIN_ROWS:
        errors.append(f"trop peu de tickets ({len(rows)} < {MIN_ROWS})")
    present = {r["category"] for r in rows}
    if present != CATEGORIES:
        errors.append(f"catégories manquantes : {CATEGORIES - present}")

    if errors:
        print(f"❌ Test set invalide ({len(errors)} problème(s)) :")
        for e in errors[:20]:
            print(f"  - {e}")
        return 1

    cats = Counter(r["category"] for r in rows)
    langs = Counter(r["language"] for r in rows)
    print(f"✅ Test set valide : {len(rows)} tickets")
    print(f"   catégories : {dict(cats)}")
    print(f"   langues    : {dict(langs)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
