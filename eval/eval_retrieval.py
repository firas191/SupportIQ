#!/usr/bin/env python3
"""Harness d'évaluation du retrieval de la base de connaissances (S5-J2).

À lancer **dans le conteneur ai-service** (où vivent les modèles et l'accès base) :

    docker compose exec ai-service python /eval/eval_retrieval.py

Compare quatre régimes sur les mêmes questions annotées :

  1. **Vectoriel seul**   — e5 + pgvector. C'est le comportement livré au S5-J1, et la référence.
  2. **BM25 seul**        — lexical pur, sans aucune sémantique.
  3. **RRF**              — fusion des deux listes par rang réciproque.
  4. **RRF + reranking**  — plus un cross-encodeur sur les candidats fusionnés.

Sans la ligne 1, on ne pourrait pas affirmer que l'hybride apporte quelque chose : « recall@5 de
0,93 » ne veut rien dire seul. C'est la **comparaison** qui est le livrable, pas le chiffre.

### Métriques

- **recall@k** : proportion de questions dont le bon fragment figure dans les k premiers. C'est ce
  qui compte pour un RAG — le générateur reçoit les k fragments, peu importe l'ordre exact entre eux.
- **MRR** (rang réciproque moyen) : 1/rang du bon fragment, moyenné. Plus fin que le recall, il
  distingue « bon fragment en 1ʳᵉ position » de « bon fragment en 5ᵉ ». C'est lui qui révèle
  l'apport du reranking, invisible sur le recall@5 quand le rappel est déjà bon.

### Appariement

L'annotation référence le couple **(source, heading)** et non l'identifiant du fragment : les `id`
changent à chaque ré-import (remplacement transactionnel), alors que le chemin de section est stable
tant que le document n'est pas réécrit. Un jeu d'évaluation annoté par `id` serait invalidé au
premier ré-import — l'erreur classique.
"""
import argparse
import asyncio
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, "/srv")  # importer le service (app.*)

from app.core import db
from app.kb import lexical, retrieval, service, store

QUESTIONS = Path("/eval/datasets/kb_questions.jsonl")
FIXTURES = Path("/fixtures/kb")
OUT = Path("/eval/results/retrieval_s5j2.md")
KS = (1, 3, 5)
POOL = 5  # nombre de fragments renvoyés par régime


def load_questions() -> list[dict]:
    return [
        json.loads(line)
        for line in QUESTIONS.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


async def ingest_fixtures() -> int:
    """Réindexe le corpus de démonstration.

    Rendre l'évaluation **reproductible** : si le corpus en base a dérivé (document supprimé,
    fichier modifié à la main), les chiffres ne seraient plus comparables d'une exécution à l'autre.
    L'ingestion est idempotente, la relancer ne coûte que le temps d'embedding.
    """
    files = sorted(FIXTURES.glob("faq*.md"))
    for path in files:
        await service.ingest(path.name, path.read_bytes())
    return len(files)


def rank_of_gold(results: list[dict], gold: dict) -> int | None:
    """Position (1-based) du fragment attendu, ou None s'il est absent des résultats."""
    for position, doc in enumerate(results, start=1):
        if doc.get("source") == gold["source"] and doc.get("heading") == gold["heading"]:
            return position
    return None


async def run_regime(name: str, questions: list[dict], search) -> dict:
    # Passe a blanc AVANT de chronometrer. Sans elle, le premier regime execute paie le chargement
    # du modele (plusieurs secondes) et sa latence n'est pas comparable aux suivants — c'est ce qui
    # faisait apparaitre le vectoriel seul (103 ms) plus lent que la fusion qui l'englobe (73 ms),
    # resultat evidemment impossible.
    await search(questions[0]["question"])

    ranks: list[int | None] = []
    started = time.perf_counter()
    for question in questions:
        results = await search(question["question"])
        ranks.append(rank_of_gold(results, question))
    elapsed = time.perf_counter() - started

    total = len(questions)
    metrics = {
        "name": name,
        "latency_ms": round(elapsed / total * 1000, 1),
        "mrr": round(sum(1 / r for r in ranks if r) / total, 4),
    }
    for k in KS:
        metrics[f"recall@{k}"] = round(sum(1 for r in ranks if r and r <= k) / total, 4)
    metrics["_ranks"] = ranks
    return metrics


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-ingest", action="store_true", help="utiliser le corpus deja en base")
    args = parser.parse_args()

    await db.connect()

    if not args.skip_ingest:
        count = await ingest_fixtures()
        print(f"Corpus reindexe : {count} documents")
    lexical.index.invalidate()

    chunks = await store.all_chunks()
    questions = load_questions()
    print(f"Corpus : {len(chunks)} fragments | Jeu d'evaluation : {len(questions)} questions\n")

    regimes = [
        ("Vectoriel seul", lambda q: retrieval.search(q, k=POOL, mode="vector")),
        ("BM25 seul", lambda q: lexical.index.search(q, POOL)),
        ("RRF (vect. + BM25)", lambda q: retrieval.search(q, k=POOL, mode="hybrid", rerank=False)),
        ("RRF + reranking", lambda q: retrieval.search(q, k=POOL, mode="hybrid", rerank=True)),
    ]

    results = []
    for name, search in regimes:
        metrics = await run_regime(name, questions, search)
        results.append(metrics)
        print(
            f"{name:22} recall@1={metrics['recall@1']:.3f}  recall@3={metrics['recall@3']:.3f}  "
            f"recall@5={metrics['recall@5']:.3f}  MRR={metrics['mrr']:.3f}  "
            f"({metrics['latency_ms']} ms/question)"
        )

    write_report(results, questions, len(chunks))
    print(f"\nRapport ecrit : {OUT}")
    await db.disconnect()


def disagreements(results: list[dict], questions: list[dict]) -> list[dict]:
    """Questions sur lesquelles les regimes ne sont pas d'accord.

    Un agregat sur 44 questions cache l'essentiel : un ecart de 0,013 de MRR, c'est **une** question
    qui a bouge. Sans savoir laquelle, on ne peut rien conclure — ni que l'hybride aide, ni qu'il
    nuit. Cette table liste les desaccords pour que la decision repose sur des cas, pas sur une
    moyenne.
    """
    rows = []
    for i, question in enumerate(questions):
        ranks = {r["name"]: r["_ranks"][i] for r in results}
        if len({str(v) for v in ranks.values()}) > 1:
            rows.append({"question": question["question"], "ranks": ranks})
    return rows


def write_report(results: list[dict], questions: list[dict], corpus_size: int) -> None:
    baseline = results[0]
    best = results[-1]

    lines = [
        "# Retrieval de la base de connaissances — évaluation (S5-J2)",
        "",
        f"Corpus : **{corpus_size} fragments** issus de 4 FAQ de démonstration.",
        f"Jeu d'évaluation : **{len(questions)} paires question/fragment** annotées à la main, "
        "couvrant les 20 sections du corpus (français et anglais).",
        "",
        "## Résultats",
        "",
        "| Régime | recall@1 | recall@3 | recall@5 | MRR | latence |",
        "|---|---|---|---|---|---|",
    ]
    for r in results:
        lines.append(
            f"| {r['name']} | {r['recall@1']:.3f} | {r['recall@3']:.3f} | "
            f"{r['recall@5']:.3f} | {r['mrr']:.3f} | {r['latency_ms']} ms |"
        )

    delta_recall = best["recall@5"] - baseline["recall@5"]
    delta_mrr = best["mrr"] - baseline["mrr"]

    diffs = disagreements(results, questions)
    lines += [
        "",
        f"## Desaccords entre regimes ({len(diffs)} questions sur {len(questions)})",
        "",
        "Rang du bon fragment. `-` = absent des 5 premiers.",
        "",
        "| Question | " + " | ".join(r["name"] for r in results) + " |",
        "|---|" + "---|" * len(results),
    ]
    for row in diffs:
        cells = " | ".join(
            str(row["ranks"][r["name"]]) if row["ranks"][r["name"]] else "-" for r in results
        )
        lines.append(f"| {row['question'][:60]} | {cells} |")

    lines += [
        "",
        "## Lecture",
        "",
        f"- **recall@5** : {baseline['recall@5']:.3f} → {best['recall@5']:.3f} "
        f"({delta_recall:+.3f}) entre le vectoriel seul et la chaîne complète.",
        f"- **MRR** : {baseline['mrr']:.3f} → {best['mrr']:.3f} ({delta_mrr:+.3f}).",
        "",
        "Le MRR est l'indicateur à regarder en priorité. Sur un corpus de cette taille le recall@5",
        "sature vite — avec 20 sections, cinq candidats couvrent un quart du corpus. Le MRR, lui,",
        "mesure si le **bon** fragment arrive en tête, ce qui est exactement ce qui compte quand",
        "l'agent Résolution n'en citera qu'un ou deux (S5-J3).",
        "",
        "## Ce que ces chiffres ne disent pas",
        "",
        "- Le corpus est **petit** (20 fragments). BM25 comme les vecteurs y sont avantagés : il y a",
        "  peu de distracteurs. Sur une base de plusieurs milliers de fragments, l'écart entre les",
        "  régimes se creuserait, en faveur de l'hybride.",
        "- Les questions sont **écrites par la même personne que le corpus**. Malgré l'effort de",
        "  reformulation (vocabulaire différent, questions indirectes), elles restent plus proches",
        "  des documents que de vraies questions clients. C'est la même réserve que sur le jeu de",
        "  tickets synthétiques du S2-J5.",
        "- L'annotation retient **un seul** fragment correct par question. Quand deux sections",
        "  répondent partiellement, le régime qui remonte l'autre est compté en échec alors que sa",
        "  réponse serait acceptable. Les chiffres sont donc un plancher, pas un plafond.",
        "",
        "## Reproduire",
        "",
        "```bash",
        "docker compose exec ai-service python /eval/eval_retrieval.py",
        "```",
        "",
        "Le corpus est réindexé au démarrage du script : deux exécutions successives sont",
        "comparables, quelle que soit la manipulation faite entre-temps dans l'écran d'administration.",
    ]

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    asyncio.run(main())
