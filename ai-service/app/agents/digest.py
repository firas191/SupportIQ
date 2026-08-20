"""Agent Digest — synthèse hebdomadaire de l'activité (S6-J4, rapport §9).

```
collect ─→ comment ─→ render ─→ fin
```

**Les chiffres viennent du code, la prose vient du modèle.** C'est la même ligne de partage que
partout ailleurs dans ce projet (S5-J3, S6-J2), et elle est ici particulièrement tranchée : un
digest est un document qu'un responsable lira sans vérifier. Une requête SQL écrite par un modèle
peut se tromper de colonne — on l'a mesuré au S6-J2 — et personne ne s'en apercevrait dans un PDF
reçu par courriel le lundi matin.

Les agrégats sont donc des requêtes **fixes, écrites à la main**, exécutées sur le rôle en lecture
seule `insight_ro`. Le modèle ne voit que leurs résultats et n'a qu'un travail : dire ce qui mérite
l'attention. C'est un vrai jugement — « la facturation a doublé » n'a pas la même valeur selon
qu'elle partait de 2 ou de 200 tickets — et c'est le seul qu'on lui confie.

**Pourquoi le rôle en lecture seule pour un travail interne.** Rien n'y oblige : le service a déjà
un pool avec tous les droits. Mais un job qui ne fait que lire n'a aucune raison de disposer d'un
accès en écriture, et réutiliser `insight_ro` rend cette propriété vérifiable plutôt que déclarée.
Le moindre privilège ne vaut que s'il s'applique aussi quand personne ne regarde.
"""
from __future__ import annotations

import logging
from datetime import date, timedelta
from typing import Any, TypedDict

from app.agents import insight_db

logger = logging.getLogger(__name__)


class DigestState(TypedDict, total=False):
    week_start: date
    stats: dict
    comment: str
    markdown: str


def monday_of(day: date) -> date:
    """Lundi de la semaine contenant `day`. La semaine ISO est l'unité du digest."""
    return day - timedelta(days=day.weekday())


# ---------------------------------------------------------------------------
# Collecte — requêtes fixes, jamais générées
# ---------------------------------------------------------------------------

_VOLUME = """
    SELECT COALESCE(SUM(tickets), 0) AS total
    FROM v_daily_volume
    WHERE day >= $1 AND day < $2
"""

_BY_CATEGORY = """
    SELECT category, SUM(tickets) AS n
    FROM v_daily_volume
    WHERE day >= $1 AND day < $2
    GROUP BY category
    ORDER BY n DESC
"""

_BY_PRIORITY = """
    SELECT priority, SUM(tickets) AS n
    FROM v_daily_volume
    WHERE day >= $1 AND day < $2
    GROUP BY priority
"""

_BY_SENTIMENT = """
    SELECT sentiment, SUM(tickets) AS n
    FROM v_daily_volume
    WHERE day >= $1 AND day < $2
    GROUP BY sentiment
"""

_DAILY = """
    SELECT day, SUM(tickets) AS n
    FROM v_daily_volume
    WHERE day >= $1 AND day < $2
    GROUP BY day
    ORDER BY day
"""

_DRAFTS = """
    SELECT COUNT(*)                                  AS proposed,
           COUNT(*) FILTER (WHERE status = 'SENT')   AS approved,
           COUNT(*) FILTER (WHERE status = 'REJECTED') AS rejected,
           COUNT(*) FILTER (WHERE was_edited)        AS edited,
           COUNT(*) FILTER (WHERE abstained)         AS abstained
    FROM v_draft_activity
    WHERE day >= $1 AND day < $2
"""


async def collect_node(state: DigestState) -> dict:
    """Agrégats de la semaine et de la précédente, pour la comparaison."""
    start = state["week_start"]
    end = start + timedelta(days=7)
    previous = start - timedelta(days=7)

    stats = {
        "week_start": start.isoformat(),
        "week_end": (end - timedelta(days=1)).isoformat(),
        "total": await _scalar(_VOLUME, start, end),
        "total_previous": await _scalar(_VOLUME, previous, start),
        "by_category": await _pairs(_BY_CATEGORY, start, end),
        "by_category_previous": await _pairs(_BY_CATEGORY, previous, start),
        "by_priority": await _pairs(_BY_PRIORITY, start, end),
        "by_sentiment": await _pairs(_BY_SENTIMENT, start, end),
        "daily": await _pairs(_DAILY, start, end),
        "drafts": await _row(_DRAFTS, start, end),
    }
    stats["variation"] = _variation(stats["total"], stats["total_previous"])
    stats["movers"] = _movers(stats["by_category"], stats["by_category_previous"])
    logger.info("Digest %s: %s tickets", start, stats["total"])
    return {"stats": stats}


async def _scalar(sql: str, *args) -> int:
    _, rows = await insight_db.run_query_args(sql, *args)
    return int(rows[0][0]) if rows and rows[0][0] is not None else 0


async def _pairs(sql: str, *args) -> list[dict]:
    _, rows = await insight_db.run_query_args(sql, *args)
    return [{"label": str(r[0]), "count": int(r[1] or 0)} for r in rows]


async def _row(sql: str, *args) -> dict:
    columns, rows = await insight_db.run_query_args(sql, *args)
    return dict(zip(columns, (int(v or 0) for v in rows[0]))) if rows else {}


def _variation(current: int, previous: int) -> float | None:
    """Variation en pourcentage. `None` quand la semaine précédente est vide.

    Diviser par zéro donnerait « +∞ % », et arrondir à 100 % laisserait croire à un doublement
    là où l'on est passé de rien à quelque chose. L'absence de valeur est plus honnête.
    """
    if previous == 0:
        return None
    return round((current - previous) / previous * 100, 1)


def _movers(current: list[dict], previous: list[dict]) -> list[dict]:
    """Catégories dont le volume a le plus bougé, en **absolu et en relatif**.

    Les deux sont nécessaires : +3 tickets sur une catégorie qui en faisait 2 est un triplement
    qui ne mérite pas une alerte, et +200 sur une catégorie qui en faisait 3000 est invisible en
    relatif mais représente le vrai travail supplémentaire de l'équipe.
    """
    before = {item["label"]: item["count"] for item in previous}
    out = []
    for item in current:
        was = before.get(item["label"], 0)
        out.append({
            "label": item["label"],
            "count": item["count"],
            "delta": item["count"] - was,
            "delta_pct": _variation(item["count"], was),
        })
    out.sort(key=lambda m: abs(m["delta"]), reverse=True)
    return out[:3]


# ---------------------------------------------------------------------------
# Commentaire — le seul endroit où un modèle intervient
# ---------------------------------------------------------------------------

SYSTEM = """You write the commentary of a weekly support digest for a support manager.

You receive the week's figures. Write 3 to 5 bullet points, in French, in this order of priority:
1. the overall trend, with the figure that justifies it;
2. the category that moved most, and whether the move is significant given its size;
3. anything that needs a decision (workload, dissatisfaction, unanswered requests).

RULES
- Use ONLY the figures given. Never invent a number, a cause, or a customer name.
- Never explain WHY something moved: you do not have that information. State what moved.
- A variation on a small base is not an alert. Say so when it applies.
- Below about 10 tickets, say the volume is too low to draw a conclusion, and stop there.
  Commenting on a trend over 1 ticket makes the whole document look unserious.
- Use the labels exactly as given. They are already written for a human reader.
- One short sentence per bullet. No introduction, no conclusion, no greeting.
- Output the bullet points only, each starting with "- "."""


async def comment_node(state: DigestState) -> dict:
    """Fait rédiger le commentaire. Une panne ici ne fait pas échouer le digest."""
    from app.agents import digest_render
    from app.core.llm import complete

    stats = state["stats"]

    # Les libellés sont traduits **avant** que le modèle les lise, pas après.
    #
    # Sinon il cite ce qu'on lui donne : le premier digest produit contenait « la catégorie
    # 'NON_ANALYSE' a connu la plus forte évolution » — du jargon de base de données dans un
    # document envoyé à un responsable. Corriger après coup demanderait de substituer dans du
    # texte libre ; ne jamais lui montrer la valeur brute supprime le problème à la source.
    def humanise(rows: list[dict]) -> list[dict]:
        return [{**row, "label": digest_render.label(row["label"])} for row in rows]

    user = (
        f"Semaine du {stats['week_start']} au {stats['week_end']}.\n"
        f"Total: {stats['total']} tickets (semaine precedente: {stats['total_previous']}, "
        f"variation: {stats['variation']}%).\n"
        f"Par categorie: {humanise(stats['by_category'])}\n"
        f"Plus fortes evolutions: {humanise(stats['movers'])}\n"
        f"Par priorite: {humanise(stats['by_priority'])}\n"
        f"Par humeur: {humanise(stats['by_sentiment'])}\n"
        f"Reponses proposees: {stats['drafts']}"
    )
    try:
        raw = await complete(
            [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}],
            # Un commentaire de chiffres n'appelle aucune creativite : deux lectures du meme
            # tableau doivent donner le meme texte. Meme choix qu'au S6-J2.
            temperature=0,
        )
        return {"comment": raw.strip()}
    except Exception as exc:  # noqa: BLE001 - quota, panne fournisseur
        # Le digest garde toute sa valeur sans commentaire : les chiffres sont la. Renoncer a
        # l'envoyer parce que la mise en mots a manque serait perdre l'essentiel pour l'accessoire.
        logger.warning("Commentaire du digest indisponible: %s", exc)
        return {"comment": ""}


# ---------------------------------------------------------------------------
# Rendu — code seul
# ---------------------------------------------------------------------------


def render_node(state: DigestState) -> dict:
    from app.agents import digest_render

    return {"markdown": digest_render.to_markdown(state["stats"], state.get("comment", ""))}


# ---------------------------------------------------------------------------
# Graphe
# ---------------------------------------------------------------------------

_graph = None


def _build_graph():
    global _graph
    if _graph is not None:
        return _graph

    from langgraph.graph import END, StateGraph

    builder = StateGraph(DigestState)
    builder.add_node("collect", collect_node)
    builder.add_node("comment", comment_node)
    builder.add_node("render", render_node)
    builder.set_entry_point("collect")
    builder.add_edge("collect", "comment")
    builder.add_edge("comment", "render")
    builder.add_edge("render", END)

    # Graphe strictement linéaire, sans reprise ni branche. Il n'apporte pas de routage ici —
    # il apporte l'**uniformité** : les trois agents du projet s'inspectent et se tracent de la
    # même façon. Une fonction séquentielle ferait le même travail ; elle ferait de cet agent le
    # seul à ne pas ressembler aux autres.
    _graph = builder.compile()
    return _graph


async def run(week_start: date | None = None) -> dict:
    """Produit le digest d'une semaine. Par défaut, la semaine écoulée."""
    if week_start is None:
        week_start = monday_of(date.today() - timedelta(days=7))

    if not insight_db.available():
        raise RuntimeError("Acces en lecture seule indisponible")

    from app.config import settings
    from app.core.run_context import run_scope

    graph = _build_graph()
    async with run_scope("digest", None, settings.budget_digest_tokens):
        final: dict[str, Any] = await graph.ainvoke({"week_start": week_start})

    return {
        "week_start": week_start.isoformat(),
        "markdown": final.get("markdown", ""),
        "stats": final.get("stats", {}),
    }
