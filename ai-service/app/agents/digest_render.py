"""Rendu du digest : Markdown, puis PDF (S6-J4).

**Le Markdown fait foi.** C'est lui qui est stocké, affiché à l'écran et converti en PDF. Le PDF
est une *vue* du Markdown, régénérable à tout moment — d'où le choix de ne pas le conserver en base
(voir V12). Un binaire dérivé qu'on stocke est un binaire qu'il faut migrer à chaque changement de
mise en forme, et dont on ne sait plus, un jour, s'il fait foi ou non.

**Aucun modèle n'intervient ici.** Mettre en forme un tableau de chiffres est une tâche sans
jugement : le confier à un modèle ajouterait de la variance sur un document destiné à être lu sans
vérification. Le commentaire, lui, a été rédigé en amont — c'est la seule partie qui demandait un
jugement.

**WeasyPrint est optionnel.** Il dépend de bibliothèques système (pango, cairo) : si elles manquent,
`to_pdf` renvoie `None` et l'appelant envoie le digest en texte plutôt que rien. Un digest sans
pièce jointe reste utile ; une chaîne qui casse parce qu'une police manque ne l'est pas.
"""
from __future__ import annotations

import logging

from app.agents.digest_render_numbers import fr_number, plural, signed

logger = logging.getLogger(__name__)

# Libellés produits. Le digest s'adresse à un responsable, pas à la base de données : il ne doit
# pas y lire `NEG` ni `FILE`. Même vocabulaire que l'interface (`shared/labels.ts`).
LABELS = {
    "TECHNIQUE": "Technique",
    "FACTURATION": "Facturation",
    "COMPTE": "Compte",
    "RECLAMATION": "Réclamation",
    "DEMANDE": "Demande",
    "NON_ANALYSE": "Non classé",
    "HIGH": "Urgente",
    "MEDIUM": "Normale",
    "LOW": "Basse",
    "INCONNUE": "Non renseignée",
    "NEG": "Mécontent",
    "NEU": "Neutre",
    "POS": "Satisfait",
    "INCONNU": "Non renseignée",
}


def label(value: str) -> str:
    return LABELS.get(value, value)


def to_markdown(stats: dict, comment: str) -> str:
    """Document complet. L'ordre suit ce qu'un responsable cherche en premier."""
    lines: list[str] = [
        f"# Synthèse hebdomadaire — semaine du {_fr_date(stats['week_start'])}",
        "",
        f"Période du {_fr_date(stats['week_start'])} au {_fr_date(stats['week_end'])}.",
        "",
        "## L'essentiel",
        "",
    ]

    total = stats.get("total", 0)
    previous = stats.get("total_previous", 0)
    variation = stats.get("variation")
    head = f"**{fr_number(total)} {plural(total, 'ticket')}** {plural(total, 'reçu')} cette semaine"

    if variation is None:
        # Semaine précédente vide : aucun pourcentage n'a de sens (voir `_variation`).
        lines.append(f"{head}.")
    elif abs(variation) < 0.05:
        # « 0,0 % de plus » est une formulation absurde qu'un lecteur relit deux fois.
        lines.append(f"{head}, autant que la semaine précédente ({fr_number(previous)}).")
    else:
        sense = "de plus" if variation > 0 else "de moins"
        lines.append(
            f"{head}, soit **{fr_number(abs(variation), 1)} % {sense}** "
            f"que la semaine précédente ({fr_number(previous)})."
        )
    lines.append("")

    if comment:
        lines += [comment, ""]

    lines += _table("## Par catégorie", stats.get("by_category", []), total)
    lines += _table("## Par priorité", stats.get("by_priority", []), total)
    lines += _table("## Humeur des clients", stats.get("by_sentiment", []), total)

    movers = stats.get("movers", [])
    if movers:
        lines += ["## Plus fortes évolutions", "",
                  "| Catégorie | Cette semaine | Écart |", "|---|---|---|"]
        for m in movers:
            pct = ""
            # Un écart relatif quasi nul n'apporte rien et alourdit la ligne.
            if m["delta_pct"] is not None and abs(m["delta_pct"]) >= 0.05:
                pct = f" ({signed(m['delta_pct'], 1)} %)"
            lines.append(
                f"| {label(m['label'])} | {fr_number(m['count'])} | "
                f"{signed(m['delta'])}{pct} |"
            )
        lines.append("")

    drafts = stats.get("drafts") or {}
    if drafts.get("proposed"):
        lines += [
            "## Réponses assistées",
            "",
            f"- {drafts.get('proposed', 0)} réponses proposées",
            f"- {drafts.get('approved', 0)} validées, {drafts.get('rejected', 0)} rejetées",
            f"- {drafts.get('edited', 0)} retouchées avant validation",
            f"- {drafts.get('abstained', 0)} sans proposition (sujet absent de la documentation)",
            "",
        ]

    lines += [
        "---",
        "",
        "_Document généré automatiquement. Les chiffres proviennent des données de la plateforme ;"
        " le commentaire est une lecture assistée et doit être vérifié avant diffusion externe._",
    ]
    return "\n".join(lines)


def _table(title: str, rows: list[dict], total: int) -> list[str]:
    if not rows:
        return []
    out = [title, "", "| Libellé | Tickets | Part |", "|---|---|---|"]
    for row in rows:
        share = f"{fr_number(row['count'] / total * 100)} %" if total else "—"
        out.append(f"| {label(row['label'])} | {fr_number(row['count'])} | {share} |")
    out.append("")
    return out


def _fr_date(iso: str) -> str:
    """`2026-08-10` -> `10/08/2026`. Un digest français ne lit pas les dates ISO."""
    try:
        year, month, day = iso.split("-")
        return f"{day}/{month}/{year}"
    except ValueError:
        return iso


# ---------------------------------------------------------------------------
# PDF
# ---------------------------------------------------------------------------

_CSS = """
@page { size: A4; margin: 18mm 16mm; }
body { font-family: 'DejaVu Sans', sans-serif; font-size: 10.5pt; color: #16181d; line-height: 1.5; }
h1 { font-size: 18pt; margin: 0 0 4pt; color: #3c34a8; }
h2 { font-size: 12pt; margin: 16pt 0 6pt; border-bottom: 1px solid #dfe3ea; padding-bottom: 3pt; }
table { width: 100%; border-collapse: collapse; margin-bottom: 6pt; }
th { text-align: left; font-size: 8.5pt; text-transform: uppercase; letter-spacing: .04em;
     color: #6a7280; border-bottom: 1px solid #dfe3ea; padding: 4pt 6pt; }
td { padding: 4pt 6pt; border-bottom: 1px solid #f0f2f6; }
/* Chiffres alignes a droite : une colonne de nombres se compare verticalement. */
td:nth-child(2), td:nth-child(3), th:nth-child(2), th:nth-child(3) { text-align: right; }
ul { margin: 6pt 0; padding-left: 14pt; }
hr { border: none; border-top: 1px solid #dfe3ea; margin: 14pt 0 8pt; }
em { color: #6a7280; font-size: 9pt; }
"""


def to_pdf(markdown_text: str) -> bytes | None:
    """Convertit le Markdown en PDF. `None` si le rendu n'est pas disponible.

    Import paresseux : `markdown` et `weasyprint` ne sont chargés qu'ici, et leur absence rend le
    PDF indisponible sans empêcher le digest d'exister. WeasyPrint dépend de bibliothèques système
    (pango, cairo) — c'est exactement le genre de dépendance qui manque un jour sur un
    environnement, et qui ne doit pas emporter la fonctionnalité entière.
    """
    try:
        import markdown as md
        from weasyprint import CSS, HTML
    except Exception as exc:  # noqa: BLE001 - dependance systeme absente
        logger.warning("Rendu PDF indisponible: %s", exc)
        return None

    try:
        html = md.markdown(markdown_text, extensions=["tables"])
        document = f"<!doctype html><html lang='fr'><meta charset='utf-8'><body>{html}</body></html>"
        return HTML(string=document).write_pdf(stylesheets=[CSS(string=_CSS)])
    except Exception as exc:  # noqa: BLE001
        logger.warning("Rendu PDF echoue: %s", exc)
        return None
