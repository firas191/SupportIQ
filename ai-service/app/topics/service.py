"""Détection des sujets émergents — orchestration (S7-J1, rapport §9).

```
tickets embeddés de la fenêtre ─→ clusters ─→ croissance (code) ─→ libellé (modèle) ─→ instantané
```

**La croissance se calcule dans la fenêtre, pas entre deux exécutions.** C'est la décision
structurante de la journée, expliquée en tête de la migration V15 : le regroupement est non
supervisé, rien ne garantit qu'un groupe de mardi soit le même objet que celui de mercredi. On
coupe donc la fenêtre en deux moitiés et on compare la seconde à la première. L'affirmation « ce
sujet monte » devient vérifiable à l'intérieur d'un seul instantané, sans appariement inventé.

Conséquence à assumer : la croissance est **relative à la fenêtre**. Sur 14 jours, elle compare les
7 derniers jours aux 7 précédents. Ce n'est pas une tendance de fond, c'est un signal
d'accélération récente — ce que cherche justement un responsable qui veut savoir ce qui a changé
cette semaine.
"""
from __future__ import annotations

import logging

from app.topics import cluster, label, store

logger = logging.getLogger(__name__)

#: Fenêtre d'analyse par défaut. Assez longue pour que la moitié « précédente » ait du volume,
#: assez courte pour qu'un sujet éteint depuis un mois n'occupe pas la liste.
DEFAULT_WINDOW_DAYS = 14

#: Borne de coût. Au-delà, la réduction de dimension devient longue sans changer les grands
#: sujets — et un instantané qui met une heure ne tournera jamais.
MAX_TICKETS = 5_000

#: Sujets conservés dans l'instantané. Une liste qu'on ne lit pas ne sert à rien ; les groupes
#: au-delà du vingtième sont des queues de distribution.
MAX_TOPICS = 20


async def detect(window_days: int = DEFAULT_WINDOW_DAYS) -> dict:
    """Calcule et enregistre un instantané de sujets. Renvoie un résumé de l'exécution."""
    from app.config import settings
    from app.core.run_context import run_scope

    # Un seul run pour toute la détection, et non un par libellé : ce qui coûte ici, c'est la
    # somme des appels de nommage. Un budget par libellé ne protégerait de rien — vingt petits
    # appels raisonnables font une facture déraisonnable.
    async with run_scope("topics", None, settings.budget_topics_tokens):
        return await _detect(window_days)


async def _detect(window_days: int) -> dict:
    tickets = await store.load_window(window_days, MAX_TICKETS)
    total = await store.count_window(window_days)

    if len(tickets) < total:
        # Écart attendu au début (les tickets importés avant le câblage du triage n'ont pas de
        # vecteur), anormal ensuite. Le dire évite de conclure « il n'y a pas de sujet » alors
        # qu'on n'a simplement rien donné à l'algorithme.
        logger.info(
            "Fenetre de %d j: %d tickets, dont %d embeddes (les autres sont ignores)",
            window_days, total, len(tickets),
        )

    clusters = cluster.find_clusters([t["vector"] for t in tickets])
    if not clusters:
        return {"window_days": window_days, "analysed": len(tickets), "topics": 0}

    midpoint = len(tickets) // 2  # les tickets sont triés par date croissante

    built: list[dict] = []
    for group in clusters[:MAX_TOPICS]:
        central = cluster.centroid_order(
            [t["vector"] for t in tickets], group.indices, label.SAMPLE_SIZE
        )
        members = [tickets[i] for i in group.indices]

        recent = sum(1 for i in group.indices if i >= midpoint)
        previous = group.size - recent

        built.append({
            "label": await label.name_cluster([tickets[i] for i in central]),
            "size": group.size,
            "recent_count": recent,
            "previous_count": previous,
            "growth": _growth(recent, previous),
            # Les exemples sont les tickets **centraux**, ceux qui justifient le libellé. Montrer
            # des cas de bordure ferait douter du groupe alors que c'est le libellé qu'on vérifie.
            "sample_ticket_ids": [tickets[i]["id"] for i in central[:3]],
            "top_category": _dominant_category(members),
        })

    # Les sujets qui montent d'abord : c'est la question posée (« qu'est-ce qui émerge ? »), pas
    # « quel est le plus gros », auquel le tableau de bord répond déjà depuis S4-J1.
    built.sort(key=lambda t: (t["growth"] is None, -(t["growth"] or 0), -t["size"]))

    saved = await store.save_snapshot(window_days, built)
    return {"window_days": window_days, "analysed": len(tickets), "topics": saved}


def _growth(recent: int, previous: int) -> float | None:
    """Croissance en pourcentage entre les deux moitiés de la fenêtre.

    `None` quand la première moitié est vide : le sujet est apparu pendant la fenêtre, et « +∞ % »
    n'est pas un chiffre. Même choix qu'au digest (S6-J4) — et l'interface en tire une information
    plus forte, « nouveau », qu'un pourcentage n'aurait pas su exprimer.
    """
    if previous == 0:
        return None
    return round((recent - previous) / previous * 100, 1)


def _dominant_category(members: list[dict]) -> str | None:
    """Catégorie la plus fréquente du groupe, si une majorité s'en dégage.

    Le seuil de 50 % n'est pas décoratif : un groupe partagé entre trois catégories n'a pas de
    catégorie dominante, et en afficher une donnerait une fausse certitude. C'est aussi un
    recoupement utile — le regroupement et le classement automatique sont deux lectures
    indépendantes du même corpus ; quand elles divergent, l'une des deux a quelque chose à dire.
    """
    counts: dict[str, int] = {}
    for member in members:
        if member["category"]:
            counts[member["category"]] = counts.get(member["category"], 0) + 1
    if not counts:
        return None
    top, n = max(counts.items(), key=lambda kv: kv[1])
    return top if n * 2 > len(members) else None
