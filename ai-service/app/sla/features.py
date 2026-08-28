"""Définition des variables du modèle de risque SLA (S7-J3, rapport §9).

**Ce module est la seule définition des variables, et il est importé par l'entraînement comme par
le service.** C'est la précaution la plus importante de la journée : le décalage entraînement /
service (*train-serve skew*) est le mode de défaillance classique d'un modèle tabulaire, et il est
silencieux. Un modèle qui reçoit `age_hours` là où il a appris `hours_remaining` ne plante pas — il
répond, et il répond n'importe quoi, avec la même assurance.

Recopier la liste des variables dans le script d'entraînement aurait suffi à ouvrir la porte : deux
listes finissent toujours par diverger d'une ligne, et rien ne le signalerait.

---

**Les vocabulaires catégoriels sont figés ici.** LightGBM travaille sur des entiers ; l'association
valeur → entier doit être identique des deux côtés. La déduire des données d'entraînement (comme le
ferait un `LabelEncoder` posé à la volée) la rendrait dépendante de l'ordre d'apparition des lignes.

Une valeur inconnue tombe sur `-1`, qui est une modalité comme une autre pour LightGBM. C'est
volontaire : un ticket non analysé n'a ni catégorie ni humeur, et c'est **une information** — il
n'est pas passé par le triage, donc personne ne l'a encore regardé.
"""
from __future__ import annotations

from datetime import datetime

#: Ordre des colonnes passées au modèle. **Ne jamais réordonner sans réentraîner** : LightGBM
#: identifie les variables par position, pas par nom.
COLUMNS = [
    # Variable dominante : le temps qu'il reste. Négative quand l'échéance est dépassée — on ne la
    # borne pas à zéro, parce que « en retard de 2 h » et « en retard de 40 h » ne sont pas le même
    # état, et l'arbre saura couper où il faut.
    "hours_remaining",
    "age_hours",
    # Encombrement de la file dans la même catégorie. C'est la variable qui rend le modèle
    # supérieur à une règle : un ticket urgent dans une file vide n'a pas le même destin qu'un
    # ticket urgent derrière quarante autres.
    "backlog",
    "hour_of_day",
    "day_of_week",
    "priority",
    "category",
    "sentiment",
    "source",
]

#: Indices des colonnes catégorielles, pour `categorical_feature` de LightGBM.
CATEGORICAL_INDICES = [COLUMNS.index(name) for name in ("priority", "category", "sentiment", "source")]

VOCABULARIES: dict[str, list[str]] = {
    "priority": ["HIGH", "MEDIUM", "LOW"],
    "category": ["TECHNIQUE", "FACTURATION", "COMPTE", "RECLAMATION", "DEMANDE"],
    "sentiment": ["NEG", "NEU", "POS"],
    "source": ["FILE", "WEBHOOK", "EMAIL", "MANUAL"],
}

#: Délai accordé par priorité, en heures. **Dupliqué dans `SlaPolicy` côté Java et dans la
#: migration V17** — trois écritures d'une même règle de trois valeurs. Assumé : la migration doit
#: rattraper l'existant sans dépendre de l'application, l'application doit dater les tickets à venir
#: sans repasser par une migration, et le modèle doit connaître le budget pour calculer la part
#: consommée. Une abstraction partagée entre SQL, Java et Python coûterait plus qu'elle ne rapporte.
BUDGET_HOURS = {"HIGH": 4.0, "MEDIUM": 24.0, "LOW": 72.0}
DEFAULT_BUDGET_HOURS = 24.0


def encode(name: str, value: str | None) -> int:
    """Modalité → entier. `-1` pour une valeur absente ou inconnue, qui est une modalité en soi."""
    vocabulary = VOCABULARIES.get(name, [])
    if value is None or value not in vocabulary:
        return -1
    return vocabulary.index(value)


def budget_hours(priority: str | None) -> float:
    return BUDGET_HOURS.get(priority or "", DEFAULT_BUDGET_HOURS)


def build(row: dict, now: datetime) -> list[float]:
    """Vecteur de variables d'un ticket, dans l'ordre de `COLUMNS`.

    `row` porte les champs bruts : `created_at`, `sla_due_at`, `priority`, `category`, `sentiment`,
    `source`, `backlog`.
    """
    created_at: datetime = row["created_at"]
    due_at: datetime | None = row.get("sla_due_at")

    age_hours = (now - created_at).total_seconds() / 3600.0
    if due_at is None:
        # Sans échéance, on retombe sur le budget de la priorité : c'est exactement ce que la
        # politique aurait posé. Inventer `hours_remaining = 0` ferait passer le ticket pour
        # dépassé alors qu'il n'a simplement jamais été daté.
        hours_remaining = budget_hours(row.get("priority")) - age_hours
    else:
        hours_remaining = (due_at - now).total_seconds() / 3600.0

    return [
        hours_remaining,
        age_hours,
        float(row.get("backlog") or 0),
        float(created_at.hour),
        float(created_at.weekday()),
        float(encode("priority", row.get("priority"))),
        float(encode("category", row.get("category"))),
        float(encode("sentiment", row.get("sentiment"))),
        float(encode("source", row.get("source"))),
    ]


def consumed_fraction(vector: list[float], priority: str | None) -> float:
    """Part du budget SLA déjà écoulée, entre 0 et 1+. Base de la règle de repli."""
    budget = budget_hours(priority)
    age = vector[COLUMNS.index("age_hours")]
    return age / budget if budget > 0 else 1.0
