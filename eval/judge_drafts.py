#!/usr/bin/env python3
"""Campagne de jugement des brouillons de réponse (S5-J5).

À lancer **dans le conteneur ai-service** (base, clés d'API et modèles y sont) :

    docker compose exec ai-service python /eval/judge_drafts.py            # 50 tickets
    docker compose exec ai-service python /eval/judge_drafts.py --limit 12 # essai court

Ce que la campagne établit, dans l'ordre d'importance :

1. **La qualité des brouillons, chiffrée** — exactitude / complétude / ton sur une grille ancrée,
   avec l'exactitude en verrou (voir `app/agents/judge.py`).
2. **Le taux d'abstention** — combien de tickets la base de connaissances ne couvre pas. C'est une
   mesure de *couverture*, reportée séparément de la qualité : les mélanger reviendrait à reprocher
   au rédacteur une documentation incomplète.
3. **La valeur de l'indicateur de faible confiance** — l'auto-vérification du S5-J3 lève un drapeau ;
   personne n'a encore vérifié qu'il prédit quoi que ce soit. On compare la note moyenne des
   brouillons signalés à celle des autres. Si l'écart est nul, le drapeau est de la décoration, et
   l'ADR-0006 le dit.

**Reprenable.** L'état vit en base : un brouillon déjà noté (`judge_score` non nul) est ignoré.
Une campagne interrompue par un quota épuisé reprend là où elle s'est arrêtée — leçon du S3-J5, où
environ 80 appels ont manqué de budget en fin d'exécution.

**Échantillonnage stratifié par catégorie**, pas aléatoire : le corpus de démonstration couvre la
facturation et le compte bien mieux que le reste. Un tirage uniforme sur 10 000 tickets donnerait un
chiffre dominé par la catégorie la plus fréquente, et masquerait que la plateforme est excellente sur
un sujet et muette sur un autre.
"""
import argparse
import asyncio
import json
import statistics
import sys
from pathlib import Path

sys.path.insert(0, "/srv")  # importer le service (app.*)

from app.agents import judge as judge_mod  # noqa: E402
from app.agents import resolution, store  # noqa: E402
from app.core import db  # noqa: E402
from app.kb import retrieval  # noqa: E402

OUT = Path("/eval/results/judge_s5j5.md")

# Journal des verdicts détaillés, et **source de reprise**.
#
# Pourquoi un fichier et pas la colonne `judge_score` : l'agrégat se déduit des trois critères,
# l'inverse est faux. Reprendre depuis la seule note ferait disparaître du rapport le taux
# d'exactitude nulle — précisément le chiffre qui décide d'un déploiement. Le détail est un
# artefact d'évaluation, il n'a rien à faire dans le schéma applicatif : il vit ici, versionnable
# et relisible.
JOURNAL = Path("/eval/results/judge_s5j5.jsonl")

DEFAULT_LIMIT = 50
PASSAGES = resolution.PASSAGES

# `NON_ANALYSE` est une strate à part entière : la plupart des tickets importés n'ont jamais été
# analysés (le triage n'a tourné que sur ceux reçus après son câblage). Les exclure plafonnait
# l'échantillon à 8 tickets sur 10 000 — et l'agent n'a de toute façon pas besoin de l'analyse pour
# rédiger, il travaille sur le sujet et le corps.
UNANALYSED = "NON_ANALYSE"
CATEGORIES = ["FACTURATION", "COMPTE", "TECHNIQUE", "RECLAMATION", "DEMANDE", UNANALYSED]

# En deçà, l'ADR-0006 interdit de conclure sur l'indicateur de faible confiance.
MIN_GROUP = 5


# ---------------------------------------------------------------------------
# Sélection des tickets
# ---------------------------------------------------------------------------


async def pick_tickets(limit: int) -> list[dict]:
    """Échantillon stratifié par catégorie, en **jointure externe** sur l'analyse.

    Exiger une analyse plafonnait l'échantillon à 8 tickets : le triage n'a tourné que sur ceux
    reçus après son câblage, pas sur les 10 000 importés avant. Les tickets non analysés forment
    donc leur propre strate — l'agent rédige à partir du sujet et du corps, l'analyse ne lui sert
    qu'à cadrer le ton.

    Les tickets d'un même import sont quasi identiques (générateur à modèles) : on **échantillonne
    régulièrement** dans chaque strate au lieu de prendre les premiers, sinon on noterait dix fois
    la même formulation et on croirait avoir mesuré dix cas.
    """
    pool = db.pool()
    per_category = max(1, limit // len(CATEGORIES))
    picked: list[dict] = []
    seen: set[int] = set()

    def keep(rows, category: str) -> None:
        for row in rows:
            if row["id"] not in seen:
                seen.add(row["id"])
                picked.append(dict(row) | {"category": category})

    async with pool.acquire() as conn:
        for category in CATEGORIES[:-1]:  # les strates analysées
            keep(await conn.fetch(
                """
                SELECT t.id, t.subject, t.body, t.language
                FROM tickets t
                JOIN analyses a ON a.ticket_id = t.id
                WHERE a.category = $1
                  AND t.merged_into_id IS NULL
                  AND coalesce(t.body, '') <> ''
                ORDER BY t.id
                LIMIT $2
                """,
                category, per_category,
            ), category)

        # Strate non analysée : c'est le gros du corpus (l'import de 10 000 tickets n'est jamais
        # passé par le triage). Un pas fixe et premier plutôt que les N premiers — ceux-ci sortent
        # tous du même modèle du générateur, on noterait dix fois la même formulation en croyant
        # avoir mesuré dix cas. Le pas est déterministe, donc une reprise retombe sur les mêmes
        # tickets et ne redépense rien.
        missing = limit - len(picked)
        if missing > 0:
            keep(await conn.fetch(
                """
                SELECT t.id, t.subject, t.body, t.language
                FROM tickets t
                LEFT JOIN analyses a ON a.ticket_id = t.id
                WHERE a.ticket_id IS NULL
                  AND t.merged_into_id IS NULL
                  AND coalesce(t.body, '') <> ''
                  AND t.id % 137 = 0
                ORDER BY t.id
                LIMIT $1
                """,
                missing,
            ), UNANALYSED)

        # Filet : si le pas est trop grossier pour le volume disponible, on complète sans lui.
        missing = limit - len(picked)
        if missing > 0:
            keep(await conn.fetch(
                """
                SELECT t.id, t.subject, t.body, t.language
                FROM tickets t
                LEFT JOIN analyses a ON a.ticket_id = t.id
                WHERE a.ticket_id IS NULL
                  AND t.merged_into_id IS NULL
                  AND coalesce(t.body, '') <> ''
                  AND NOT (t.id = ANY($1::bigint[]))
                ORDER BY t.id
                LIMIT $2
                """,
                list(seen) or [0], missing,
            ), UNANALYSED)

    return picked[:limit]


def load_journal() -> dict[int, dict]:
    """Verdicts des campagnes précédentes, indexés par ticket."""
    if not JOURNAL.exists():
        return {}
    done: dict[int, dict] = {}
    for line in JOURNAL.read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            done[row["ticket_id"]] = row
    return done


def append_journal(row: dict) -> None:
    """Écrit le verdict **immédiatement**, pas en fin de campagne.

    Une exécution de 50 tickets dure plusieurs minutes et peut s'arrêter sur un quota épuisé. Tout
    garder en mémoire jusqu'à la fin reviendrait à perdre l'intégralité du travail au premier
    incident — exactement ce qui s'est produit au S3-J5.
    """
    JOURNAL.parent.mkdir(parents=True, exist_ok=True)
    with JOURNAL.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + "\n")


async def existing_draft(ticket_id: int) -> dict | None:
    """Dernier brouillon non rejeté du ticket, s'il en existe un."""
    pool = db.pool()
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            """
            SELECT id, content, tone, low_confidence, abstained, attempts, judge_score
            FROM draft_responses
            WHERE ticket_id = $1 AND status <> 'REJECTED'
            ORDER BY created_at DESC
            LIMIT 1
            """,
            ticket_id,
        )
    return dict(row) if row else None


# ---------------------------------------------------------------------------
# Campagne
# ---------------------------------------------------------------------------


async def run_one(ticket: dict, tone: str) -> dict:
    """Assure l'existence d'un brouillon, puis le note. Renvoie une ligne de résultat."""
    ticket_id = ticket["id"]
    draft = await existing_draft(ticket_id)

    if draft is None:
        try:
            result = await resolution.run(ticket_id, tone)
        except Exception as exc:  # noqa: BLE001 - quota, agent indisponible
            return {"ticket_id": ticket_id, "category": ticket["category"], "error": str(exc)[:120]}
        draft = {
            "id": result.get("draft_id"),
            "content": result.get("content", ""),
            "tone": result.get("tone", tone),
            "low_confidence": result.get("low_confidence", False),
            "abstained": result.get("abstained", False),
            "attempts": result.get("attempts", 0),
            "judge_score": None,
        }

    row = {
        "ticket_id": ticket_id,
        "draft_id": draft["id"],
        "category": ticket["category"],
        "abstained": bool(draft["abstained"]),
        "low_confidence": bool(draft["low_confidence"]),
        "attempts": draft["attempts"],
    }

    question = f"{ticket.get('subject') or ''}\n\n{ticket.get('body') or ''}".strip()
    # Les passages ne sont pas stockés dans le brouillon : on rejoue la recherche, qui est
    # déterministe à base de connaissances constante. Caveat assumé — si la base a changé entre la
    # rédaction et le jugement, le juge ne voit pas exactement ce que le rédacteur a vu. Dans une
    # campagne où l'on génère et note dans la foulée, le cas ne se présente pas.
    passages = await retrieval.search(question, k=PASSAGES, mode="hybrid")

    if not judge_mod.is_judgeable(row["abstained"], passages):
        row["score"] = None
        return row

    verdict = await judge_mod.judge(question, passages, draft["content"], draft["tone"])
    if verdict is None:
        row["score"] = None
        row["error"] = "juge indisponible"
        return row

    score = judge_mod.aggregate(verdict)
    row.update(
        score=score,
        accuracy=verdict.accuracy,
        completeness=verdict.completeness,
        tone_score=verdict.tone,
        reason=verdict.reason,
        judged_by=verdict.judged_by,
    )
    if draft["id"] is not None:
        await store.set_judge_score(draft["id"], score)
    return row


async def main() -> int:
    parser = argparse.ArgumentParser(description="LLM-as-judge sur les brouillons (S5-J5)")
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    parser.add_argument("--tone", default="formal", choices=["formal", "empathetic"])
    args = parser.parse_args()

    await db.connect()
    if db.pool() is None:
        print("ERREUR : base injoignable.", file=sys.stderr)
        return 1

    tickets = await pick_tickets(args.limit)
    if not tickets:
        print("ERREUR : aucun ticket exploitable en base.", file=sys.stderr)
        return 1

    # L'écart entre demandé et obtenu est dit, pas subi en silence : une campagne qui rend 8 lignes
    # au lieu de 50 ne se lit pas du tout de la même façon.
    if len(tickets) < args.limit:
        print(f"ATTENTION : {len(tickets)} tickets disponibles pour {args.limit} demandés.",
              file=sys.stderr)
    print(f"{len(tickets)} tickets sélectionnés", file=sys.stderr)

    done = load_journal()
    rows: list[dict] = []
    for index, ticket in enumerate(tickets, start=1):
        cached = done.get(ticket["id"])
        if cached is not None:
            rows.append(cached)
            print(f"  [{index}/{len(tickets)}] ticket {ticket['id']:>6} (déjà noté)",
                  file=sys.stderr)
            continue

        row = await run_one(ticket, args.tone)
        append_journal(row)
        rows.append(row)
        mark = (
            "abstention" if row.get("abstained")
            else f"{row['score']:.2f}" if row.get("score") is not None
            else "non noté"
        )
        print(f"  [{index}/{len(tickets)}] ticket {row['ticket_id']:>6} {mark}", file=sys.stderr)

    _write_report(rows, args)
    await db.disconnect()

    judged = [r for r in rows if r.get("score") is not None and not r.get("abstained")]
    print(f"\nRapport écrit : {OUT}", file=sys.stderr)
    if judged:
        print(f"note moyenne = {statistics.mean(r['score'] for r in judged):.2f} "
              f"sur {len(judged)} brouillons notés", file=sys.stderr)
    return 0


# ---------------------------------------------------------------------------
# Rapport
# ---------------------------------------------------------------------------


def _mean(values: list[float]) -> float | None:
    return statistics.mean(values) if values else None


def _fmt(value: float | None) -> str:
    return "—" if value is None else f"{value:.2f}"


def _write_report(rows: list[dict], args) -> None:
    judged = [r for r in rows if r.get("score") is not None and not r.get("abstained")]
    abstained = [r for r in rows if r.get("abstained")]
    failed = [r for r in rows if r.get("error")]

    scores = [r["score"] for r in judged]
    unusable = [r for r in judged if r.get("accuracy") == 0]

    shortfall = (
        f" — **{args.limit} demandés**, la base n'en fournit pas davantage"
        if len(rows) < args.limit else ""
    )

    md = [
        "# LLM-as-judge sur les brouillons de réponse (S5-J5)\n",
        f"- Échantillon : **{len(rows)} tickets**, stratifiés par catégorie, ton "
        f"`{args.tone}`{shortfall}.",
        "- Grille : exactitude / complétude / ton, niveaux 0-1-2 ancrés "
        "(`ai-service/app/agents/judge.py`).",
        "- Note globale = moyenne des trois critères, **ramenée à 0 si l'exactitude est nulle** : "
        "un brouillon qui affirme un fait absent des sources est inutilisable, pas perfectible.",
        "- Juge : modèle **distinct du rédacteur** (70b contre 8b) — un modèle qui se note se "
        "préfère.\n",
        "## Vue d'ensemble\n",
        "| | Nombre | Part |",
        "|---|---|---|",
        f"| Brouillons notés | {len(judged)} | {len(judged) / len(rows):.0%} |",
        f"| Abstentions (hors périmètre de la base) | {len(abstained)} | "
        f"{len(abstained) / len(rows):.0%} |",
        f"| Non aboutis (quota, panne) | {len(failed)} | {len(failed) / len(rows):.0%} |",
    ]

    if judged:
        md += [
            "\n## Qualité des brouillons notés\n",
            "| Critère | Moyenne (0-2) |",
            "|---|---|",
            f"| Exactitude | {_fmt(_mean([r['accuracy'] for r in judged if 'accuracy' in r]))} |",
            f"| Complétude | "
            f"{_fmt(_mean([r['completeness'] for r in judged if 'completeness' in r]))} |",
            f"| Ton | {_fmt(_mean([r['tone_score'] for r in judged if 'tone_score' in r]))} |",
            f"\n**Note globale moyenne : {_fmt(_mean(scores))}** "
            f"(médiane {_fmt(statistics.median(scores) if scores else None)}).",
            f"\n**Brouillons inutilisables** (exactitude = 0) : **{len(unusable)}** sur "
            f"{len(judged)} ({len(unusable) / len(judged):.0%}). C'est le chiffre qui compte pour "
            "un déploiement : les autres décrivent du travail de relecture, celui-ci décrit une "
            "information fausse proposée à l'envoi.",
        ]

        # --- Le drapeau de faible confiance prédit-il quelque chose ? ---
        flagged = [r["score"] for r in judged if r["low_confidence"]]
        clean = [r["score"] for r in judged if not r["low_confidence"]]
        md += [
            "\n## L'indicateur de faible confiance prédit-il la note ?\n",
            "| Groupe | Effectif | Note moyenne |",
            "|---|---|---|",
            f"| Signalés « à relire » | {len(flagged)} | {_fmt(_mean(flagged))} |",
            f"| Non signalés | {len(clean)} | {_fmt(_mean(clean))} |",
        ]
        # L'ADR-0006 fixe le seuil d'effectif AVANT la mesure. Le rapport l'applique lui-même :
        # afficher un écart en dessous du seuil inviterait à le lire, et la règle serait contournée
        # par sa propre présentation.
        if len(flagged) < MIN_GROUP or len(clean) < MIN_GROUP:
            md.append(
                f"\n**Aucune décision possible.** L'ADR-0006 exige au moins {MIN_GROUP} brouillons "
                "par groupe ; l'écart n'est pas affiché ici parce qu'un chiffre montré est un "
                "chiffre lu, et celui-ci ne distinguerait pas un signal d'un tirage au sort. Le "
                "seuil a été fixé avant la mesure, il s'applique même quand il gêne."
            )
        else:
            gap = _mean(clean) - _mean(flagged)
            md.append(
                f"\nÉcart : **{gap:+.2f}**. Un écart proche de zéro signifierait que "
                "l'auto-vérification signale au hasard — le bandeau d'avertissement serait alors "
                "de la décoration, et apprendrait aux agents à ignorer les avertissements. "
                "Critère de décision fixé **avant** la mesure : voir ADR-0006."
            )

        # --- Par catégorie : où la base de connaissances porte, et où elle ne porte pas ---
        md += ["\n## Par catégorie\n", "| Catégorie | Notés | Note moyenne | Abstentions |",
               "|---|---|---|---|"]
        for category in CATEGORIES:
            cat_judged = [r["score"] for r in judged if r["category"] == category]
            cat_abst = sum(1 for r in abstained if r["category"] == category)
            total = sum(1 for r in rows if r["category"] == category)
            if total:
                md.append(f"| {category} | {len(cat_judged)} | {_fmt(_mean(cat_judged))} | "
                          f"{cat_abst}/{total} |")
        md.append(
            "\nUn taux d'abstention élevé sur une catégorie ne dit rien du rédacteur : il dit que "
            "la base de connaissances ne couvre pas ce sujet. C'est une consigne de travail pour "
            "l'administrateur, pas un défaut du modèle."
        )

        # --- Les pires cas, nommés ---
        worst = sorted((r for r in judged if "reason" in r), key=lambda r: r["score"])[:5]
        if worst:
            md += ["\n## Les cinq notes les plus basses\n",
                   "| Ticket | Note | Exactitude | Reproche du juge |", "|---|---|---|---|"]
            for r in worst:
                md.append(f"| {r['ticket_id']} | {r['score']:.2f} | {r.get('accuracy', '—')} | "
                          f"{r.get('reason', '')} |")
            md.append(
                "\nL'agrégat ne suffit jamais : c'est en lisant ces cas qu'on sait s'il faut "
                "corriger le prompt, la base de connaissances ou la recherche. Leçon du S5-J2, où "
                "0,013 de MRR représentait **une** question."
            )

    md += [
        "\n## Limites\n",
        "- Le juge est un modèle, pas un client ni un agent expérimenté. Il vérifie la cohérence "
        "entre un texte et des passages ; il ne dit pas si la réponse aurait satisfait la personne.",
        "- Les tickets sont **synthétiques** (S2-J5) et la base de connaissances écrite pour eux : "
        "la couverture mesurée ici est plus favorable qu'elle ne le serait sur un corpus réel.",
        "- Une seule note par brouillon. Mesurer la stabilité du juge demanderait de noter deux "
        "fois le même brouillon et de comparer — non fait, faute de budget de jetons.",
    ]

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(md) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
