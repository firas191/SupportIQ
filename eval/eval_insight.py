#!/usr/bin/env python3
"""Suite d'évaluation du text-to-SQL (S6-J2, rapport §9 — objectif ≥ 80 % de réussite).

À lancer **dans le conteneur ai-service** :

    docker compose exec ai-service python /eval/eval_insight.py
    docker compose exec ai-service python /eval/eval_insight.py --only 3,7,12   # sous-ensemble

**La comparaison porte sur le résultat, pas sur le texte du SQL.** Deux requêtes correctes
s'écrivent rarement pareil : `COUNT(*) FROM v_tickets WHERE status='NEW'` et
`SELECT new_tickets FROM v_ticket_stats` répondent à la même question avec la même valeur. Comparer
les chaînes mesurerait la ressemblance stylistique avec ma propre écriture, pas la justesse.

**Deux niveaux de correspondance sont reportés.**
- *stricte* : mêmes lignes, mêmes valeurs, dans le même ordre de colonnes (l'ordre des **lignes**
  est ignoré, un `ORDER BY` différent ne change pas la réponse à « combien »).
- *souple* : mêmes valeurs, ordre des colonnes indifférent. Un modèle qui renvoie `(nb, category)`
  au lieu de `(category, nb)` a compris la question ; il a juste présenté autrement.

Le chiffre annoncé est le **strict**. Le souple est reporté à côté pour montrer combien d'échecs
ne sont que de la présentation — l'écart entre les deux est une information, pas un ajustement.

**Trois questions attendent un refus** (`expect: impossible`) : elles portent sur des données que
les vues n'exposent pas (adresse client, corps du message, salaires). Un agent qui répond quand
même à celles-là est plus dangereux qu'un agent qui se trompe de colonne.

**Aucune des deux questions d'exemple du prompt ne figure dans la suite.** Sinon la mesure porterait
sur ma capacité à écrire de bons exemples, pas sur celle de l'agent à généraliser.
"""
import argparse
import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, "/srv")

from app.agents import insight, insight_db, sql_guard  # noqa: E402

QUESTIONS = Path("/eval/datasets/insight_questions.jsonl")
OUT = Path("/eval/results/insight_s6j2.md")


# ---------------------------------------------------------------------------
# Comparaison de résultats
# ---------------------------------------------------------------------------


def normalise(value):
    """Ramène une valeur à une forme comparable.

    Les décimaux arrondis à 4 chiffres : `AVG()` peut différer au dernier bit selon l'ordre
    d'agrégation choisi par le planificateur, et refuser une réponse pour 1e-15 serait absurde.
    Les booléens avant les nombres : en Python `True == 1`, et confondre les deux ferait passer
    des réponses fausses.
    """
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return round(float(value), 4)
    return str(value)


def canonical(rows: list[list], sort_within_row: bool = False) -> list:
    """Multiset de lignes normalisées, trié pour être comparable."""
    out = []
    for row in rows:
        values = [normalise(v) for v in row]
        if sort_within_row:
            # Tri par représentation textuelle : les types sont hétérogènes dans une ligne.
            values = sorted(values, key=lambda v: (v is None, str(v)))
        out.append(tuple(values))
    return sorted(out, key=lambda t: tuple(str(v) for v in t))


def compare(expected: list[list], actual: list[list]) -> tuple[bool, bool]:
    """(correspondance stricte, correspondance souple)."""
    strict = canonical(expected) == canonical(actual)
    loose = canonical(expected, True) == canonical(actual, True)
    return strict, loose


# ---------------------------------------------------------------------------
# Campagne
# ---------------------------------------------------------------------------


def load(only: set[int] | None) -> list[dict]:
    rows = [json.loads(line) for line in QUESTIONS.read_text(encoding="utf-8").splitlines() if line.strip()]
    return [r for r in rows if only is None or r["id"] in only]


async def reference_rows(sql: str) -> list[list]:
    """Exécute le SQL de référence — **à travers la garde**.

    Cela vérifie au passage que mes propres requêtes de référence respectent les règles imposées au
    modèle. Une référence qui ne passerait pas la garde serait un barème injuste.
    """
    validated = sql_guard.validate(sql)
    _, rows = await insight_db.run_query(validated)
    return rows


async def run_case(case: dict) -> dict:
    result = {"id": case["id"], "question": case["question"], "expect": case["expect"]}

    try:
        produced = await insight.answer(case["question"])
    except insight.InsightError as exc:
        result["error"] = exc.code
        result["sql"] = exc.sql or ""
        # Un refus est la bonne réponse aux questions hors périmètre.
        result["ok"] = case["expect"] == "impossible" and exc.code in {"out_of_scope", "sql_failed"}
        result["loose"] = result["ok"]
        return result

    result["sql"] = produced["sql"]
    result["attempts"] = produced["attempts"]
    result["chart"] = produced["chart"]["type"]

    if case["expect"] == "impossible":
        # L'agent a produit un résultat là où il aurait dû refuser : échec, et le plus grave du lot.
        result["ok"] = False
        result["loose"] = False
        result["note"] = "a repondu au lieu de refuser"
        return result

    result["reference"] = case["sql"]
    try:
        expected = await reference_rows(case["sql"])
    except Exception as exc:  # noqa: BLE001 - référence fautive : c'est MON erreur, à voir tout de suite
        result["ok"] = False
        result["loose"] = False
        result["note"] = f"SQL de reference invalide: {str(exc)[:100]}"
        return result

    strict, loose = compare(expected, produced["rows"])
    result["ok"] = strict
    result["loose"] = loose
    result["expected_rows"] = len(expected)
    result["actual_rows"] = len(produced["rows"])
    return result


async def main() -> int:
    parser = argparse.ArgumentParser(description="Suite text-to-SQL (S6-J2)")
    parser.add_argument("--only", help="identifiants separes par des virgules")
    args = parser.parse_args()
    only = {int(x) for x in args.only.split(",")} if args.only else None

    await insight_db.connect()
    if not insight_db.available():
        print("ERREUR : acces en lecture seule indisponible (role insight_ro).", file=sys.stderr)
        return 1

    cases = load(only)
    print(f"{len(cases)} questions", file=sys.stderr)

    results = []
    for index, case in enumerate(cases, start=1):
        res = await run_case(case)
        results.append(res)
        mark = "OK " if res["ok"] else ("~  " if res.get("loose") else "KO ")
        print(f"  [{index}/{len(cases)}] {mark} #{res['id']:>2} {res['question'][:60]}",
              file=sys.stderr)

    _write_report(results)
    await insight_db.disconnect()

    strict = sum(1 for r in results if r["ok"])
    print(f"\nRapport : {OUT}", file=sys.stderr)
    print(f"Reussite stricte : {strict}/{len(results)} ({strict / len(results):.0%})", file=sys.stderr)
    return 0


# ---------------------------------------------------------------------------
# Rapport
# ---------------------------------------------------------------------------


def _write_report(results: list[dict]) -> None:
    total = len(results)
    strict = sum(1 for r in results if r["ok"])
    loose = sum(1 for r in results if r.get("loose"))
    repaired = [r for r in results if r.get("attempts", 1) > 1]
    refusals = [r for r in results if r["expect"] == "impossible"]
    refusals_ok = sum(1 for r in refusals if r["ok"])

    md = [
        "# Suite d'evaluation text-to-SQL (S6-J2)\n",
        f"- **{total} questions**, dont {len(refusals)} attendant un refus.",
        "- Comparaison par **resultat d'execution**, jamais par texte du SQL : deux requetes "
        "correctes s'ecrivent rarement pareil.",
        "- Les deux questions d'exemple du prompt sont **exclues** de la suite.",
        "- Objectif du rapport §9 : **≥ 80 %**.\n",
        "## Resultat\n",
        "| Mesure | Valeur |",
        "|---|---|",
        f"| **Reussite stricte** | **{strict}/{total} ({strict / total:.0%})** |",
        f"| Reussite souple (ordre des colonnes ignore) | {loose}/{total} ({loose / total:.0%}) |",
        f"| Refus corrects sur questions hors perimetre | {refusals_ok}/{len(refusals)} |",
        f"| Requetes reparees par la boucle | {len(repaired)} |",
    ]

    if loose > strict:
        md.append(
            f"\nL'ecart strict/souple ({loose - strict} question(s)) mesure les reponses **justes "
            "mais presentees autrement** — colonnes dans un autre ordre. Ce n'est pas une erreur "
            "de comprehension ; c'est reporte separement plutot que masque dans le chiffre principal."
        )

    if repaired:
        md += ["\n## Reparations\n",
               "La boucle a corrige une erreur SQL sur ces questions :\n",
               "| # | Question | Essais | Reussite |", "|---|---|---|---|"]
        for r in repaired:
            md.append(f"| {r['id']} | {r['question'][:60]} | {r['attempts']} | "
                      f"{'oui' if r['ok'] else 'non'} |")
        md.append("\nChaque ligne est une requete qui aurait echoue sans reinjection du message "
                  "d'erreur de PostgreSQL.")

    failures = [r for r in results if not r["ok"]]
    if failures:
        # Le SQL généré est affiché en entier. Sans lui, « résultat différent (5 lignes contre 1) »
        # n'apprend rien : on ne sait pas si le modèle a mal compris la question, mal choisi la vue,
        # ou simplement groupé là où on attendait un total. Un rapport d'échec sans la sortie
        # fautive oblige à tout rejouer pour diagnostiquer.
        md += ["\n## Echecs\n"]
        for r in failures:
            cause = r.get("note") or r.get("error") or (
                "ordre des colonnes uniquement" if r.get("loose")
                else f"resultat different ({r.get('actual_rows', '?')} lignes contre "
                     f"{r.get('expected_rows', '?')} attendues)"
            )
            md.append(f"\n### #{r['id']} — {r['question']}\n")
            md.append(f"**Cause** : {cause}\n")
            md.append("SQL genere :\n")
            md.append("```sql\n" + (r.get("sql") or "(aucun)") + "\n```\n")
            if r.get("reference"):
                md.append("SQL de reference :\n")
                md.append("```sql\n" + r["reference"] + "\n```\n")
        md.append("\nLire ces cas un par un : c'est la que se decide s'il faut corriger le prompt, "
                  "les vues, ou la question elle-meme (une question ambigue n'a pas de reponse de "
                  "reference legitime).")

    md += [
        "\n## Limites\n",
        "- Les questions sont **precises par construction**. Une question ambigue (« repartis les "
        "humeurs » : avec ou sans les tickets non analyses ?) admet plusieurs reponses justes, et "
        "aucun bareme ne peut trancher. La suite mesure la traduction de questions claires.",
        "- Le SQL de reference est ecrit par la meme personne que le prompt. Un biais subsiste : "
        "je formule les questions comme je concois les vues.",
        "- Les donnees sont celles de la base de developpement. Un resultat vide des deux cotes "
        "compte comme une reussite, alors qu'il ne demontre pas grand-chose.",
    ]

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(md) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
