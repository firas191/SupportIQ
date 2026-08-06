"""Citations d'un brouillon de réponse (S5-J3).

**Pourquoi ce module est séparé et sans dépendance.** La vérification des citations est la garantie
la plus importante de l'agent Résolution : c'est elle qui distingue une réponse *fondée sur la
documentation* d'une réponse *inventée qui en a l'air*. Elle doit donc être testable sans LLM, sans
base et sans réseau — sinon elle ne sera jamais vraiment testée.

**Pourquoi des marqueurs numériques `[1]` et non des identifiants.** On numérote les passages dans
le prompt et on demande au modèle de citer `[1]`, `[2]`. Lui demander de recopier un identifiant de
fragment (`[12847]`) reviendrait à lui demander de reproduire une chaîne arbitraire — c'est
exactement le genre de tâche où un modèle hallucine. Un petit entier borné par le nombre de passages
fournis est vérifiable en une opération d'appartenance : `marqueur <= len(passages)`.

**Le contrôle est déterministe, donc gratuit.** Vérifier que `[3]` existe parmi 5 passages est un
test d'appartenance, pas un appel de modèle. C'est ce qui permet de court-circuiter : un brouillon
sans citation valide est renvoyé en re-génération **sans dépenser** l'appel LLM de vérification
sémantique.
"""
from __future__ import annotations

import re

# Marqueur de citation : [1], [12]. Le motif exige des chiffres uniquement — « [voir plus haut] »
# n'est pas une citation et ne doit pas être compté comme telle.
_MARKER = re.compile(r"\[(\d{1,2})\]")

# ---------------------------------------------------------------------------
# Détection d'abstention
# ---------------------------------------------------------------------------
#
# Quand la documentation ne couvre pas la question, le brouillon doit pouvoir dire « je ne sais
# pas » SANS être renvoyé en re-génération pour l'obliger à inventer une source.
#
# **Deux niveaux, et l'ordre compte.** La première version ne reposait que sur des motifs de
# formulation ; elle a échoué en conditions réelles sur « les informations ... ne sont pas
# disponibles » (ordre des mots inversé par rapport au motif attendu). Résultat : trois générations
# au lieu d'une, et un drapeau « faible confiance » levé sur un brouillon pourtant irréprochable.
#
# Le correctif ne consiste pas à ajouter des motifs indéfiniment — les formulations d'abstention
# sont ouvertes, cette course est perdue d'avance. On demande désormais au modèle d'émettre un
# **jeton explicite** quand il s'abstient : la détection devient exacte et indépendante de la
# langue. Les motifs restent, en **repli**, pour le cas où le modèle omet le jeton.

SENTINEL = "[NO_ANSWER]"

_FALLBACK = re.compile(
    r"(aucune?\s+information"
    r"|n'?ai\s+pas\s+trouv"
    r"|pas\s+(?:d'|de\s+)information"
    r"|informations?[^.]{0,40}(?:ne\s+sont\s+pas|n'est\s+pas)\s+disponibles?"
    r"|ne\s+(?:sont|est)\s+pas\s+disponibles?\s+dans\s+les\s+passages"
    r"|ne\s+(?:trouve|dispose|figure)"
    r"|ne\s+(?:permettent|permet)\s+pas\s+de\s+r[ée]pondre"
    r"|(?:could|can)\s?not\s+find"
    r"|(?:is|are)\s+not\s+available\s+in\s+the\s+(?:provided\s+)?passages"
    r"|no\s+(?:relevant\s+)?information"
    r"|not\s+covered"
    r"|traiter\s+manuellement"
    r"|handle\s+this\s+ticket\s+manually)",
    re.IGNORECASE,
)


def extract_markers(text: str) -> list[int]:
    """Marqueurs cités, dédoublonnés, dans l'ordre d'apparition."""
    seen: list[int] = []
    for match in _MARKER.finditer(text):
        value = int(match.group(1))
        if value not in seen:
            seen.append(value)
    return seen


def is_abstention(text: str) -> bool:
    """Le brouillon reconnaît-il explicitement ne pas savoir ?

    Jeton explicite d'abord (exact, insensible à la langue et à la tournure), motifs de formulation
    en repli si le modèle a omis le jeton.
    """
    return SENTINEL in text or bool(_FALLBACK.search(text))


def strip_sentinel(text: str) -> str:
    """Retire le jeton d'abstention : il pilote le code, il ne s'affiche jamais au client."""
    return text.replace(SENTINEL, "").strip()


def validate(draft: str, passage_count: int) -> tuple[list[int], list[str]]:
    """Contrôle déterministe des citations.

    Renvoie les marqueurs valides et la liste des reproches. Une liste de reproches vide signifie
    que le brouillon est **citable** — pas qu'il est bon : la pertinence du contenu est vérifiée
    séparément, par le modèle.
    """
    issues: list[str] = []
    markers = extract_markers(draft)

    if not markers:
        # Une abstention assumée n'a pas à citer.
        if not is_abstention(draft):
            issues.append("no_citation")
        return [], issues

    out_of_range = [m for m in markers if m < 1 or m > passage_count]
    if out_of_range:
        # Le modèle a cité une source qui ne lui a pas été fournie : c'est une hallucination de
        # référence, le cas exact que le contrôle existe pour attraper.
        issues.append(f"invalid_citation:{','.join(str(m) for m in out_of_range)}")

    valid = [m for m in markers if 1 <= m <= passage_count]
    if not valid:
        issues.append("no_valid_citation")
    return valid, issues


def build(markers: list[int], passages: list[dict]) -> list[dict]:
    """Transforme les marqueurs en citations exploitables par l'interface (S5-J4).

    On conserve `chunk_id`, `source` et `heading` : c'est le triplet qui permettra de surligner le
    passage exact dans l'écran de la base de connaissances. Un extrait court est joint pour que
    l'agent voie sur quoi repose la phrase sans changer d'écran.
    """
    citations: list[dict] = []
    for marker in markers:
        passage = passages[marker - 1]
        citations.append(
            {
                "marker": marker,
                "chunk_id": passage.get("id"),
                "source": passage.get("source"),
                "heading": passage.get("heading"),
                "excerpt": (passage.get("content") or "")[:280],
            }
        )
    return citations
