"""Choix du graphique à partir de la forme du résultat (S6-J2).

**Décidé par le code, pas par le modèle.** Le rapport §6 prévoit un `chart_spec` dans la réponse de
l'agent Insight, et la solution évidente est de le demander au modèle en même temps que la synthèse.
On ne le fait pas, et la raison est la règle formulée au S5-J3 : *le modèle là où il y a un
jugement, du code partout ailleurs.*

Choisir un type de graphique ne demande aucun jugement. C'est une table de décision sur trois
entrées : le nombre de lignes, le type de la colonne d'étiquettes, le nombre de colonnes numériques.
Le confier à un modèle ajoute trois modes de défaillance — un nom de colonne inventé, un type de
graphique inexistant, un JSON cassé — pour zéro gain. La synthèse en langage naturel, elle, demande
un vrai jugement : c'est elle qu'on laisse au modèle.

**Pourquoi jamais de camembert.** Un graphique en anneau affirme que les valeurs sont *les parts
d'un tout*. Rien ici ne permet de le vérifier : « nombre de tickets par catégorie » l'est, « délai
moyen par catégorie » ne l'est pas du tout, et les deux ont exactement la même forme de résultat.
Une barre n'affirme rien de tel. On ne dessine pas une affirmation qu'on ne peut pas contrôler.
"""
from __future__ import annotations

import re

#: Au-delà, un graphique à barres devient une forêt illisible ; le tableau reste plus utile.
MAX_CATEGORIES = 40

#: Noms de colonnes qui portent une dimension temporelle dans nos vues.
TEMPORAL_NAMES = frozenset({"day", "date", "created_at", "hour_of_day", "week", "month"})

_ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}([T ]|$)")


def derive(columns: list[str], rows: list[list]) -> dict:
    """Spécification de graphique pour un résultat de requête.

    Renvoie toujours un dictionnaire, avec `type = "none"` quand aucun graphique n'aide. `reason`
    explique le choix : l'écran du S6-J3 peut ainsi dire « une seule valeur, pas de graphique »
    plutôt que d'afficher un cadre vide, qui se lit comme une panne.
    """
    if not columns or not rows:
        return _none("empty_result")

    # Classement en trois familles, dans cet ordre. **Le temporel prime sur le numérique** : une
    # heure de la journée (0-23) est un entier, mais c'est un axe, pas une mesure. Sans cette
    # priorité, « combien de tickets par heure » n'aurait aucune colonne d'étiquettes et ne
    # produirait aucun graphique — défaut trouvé en écrivant les tests.
    temporal = [i for i, name in enumerate(columns) if _is_temporal(i, name, rows)]
    numeric = [
        i for i, _ in enumerate(columns)
        if i not in temporal and _is_numeric_column(i, rows)
    ]
    labels = [i for i, _ in enumerate(columns) if i not in temporal and i not in numeric]

    if not numeric:
        return _none("no_numeric_column")

    if not temporal and not labels:
        if len(rows) == 1:
            # Un seul nombre : « 412 tickets » se lit mieux en chiffre qu'en barre unique.
            return _none("single_value")
        return _none("no_label_column")

    y = columns[numeric[0]]

    if temporal:
        # Une série temporelle se lit en courbe : la continuité entre deux points a un sens,
        # ce qui n'est pas vrai entre deux catégories. Le plafond de cardinalité ne s'applique
        # pas ici — une courbe de soixante jours reste lisible.
        return {"type": "line", "x": columns[temporal[0]], "y": y, "reason": "temporal_x"}

    if len(rows) > MAX_CATEGORIES:
        return _none("too_many_categories")

    return {"type": "bar", "x": columns[labels[0]], "y": y, "reason": "categorical_x"}


# ---------------------------------------------------------------------------
# Détection
# ---------------------------------------------------------------------------


def _is_numeric_column(index: int, rows: list[list]) -> bool:
    """Une colonne est numérique si toutes ses valeurs non nulles le sont.

    Les booléens sont exclus : en Python ils *sont* des entiers, et tracer une courbe de `True`
    et `False` n'a pas de sens. C'est le genre de détail qui ne se voit qu'à l'exécution.
    """
    seen = False
    for row in rows:
        value = row[index] if index < len(row) else None
        if value is None:
            continue
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return False
        seen = True
    return seen


def _is_temporal(index: int, name: str, rows: list[list]) -> bool:
    if name.lower() in TEMPORAL_NAMES:
        return True
    # Les dates traversent la couche d'exécution en chaînes ISO (`insight_db._plain`) : on les
    # reconnaît à leur forme plutôt qu'à leur type, qui a déjà été perdu.
    for row in rows:
        value = row[index] if index < len(row) else None
        if isinstance(value, str) and _ISO_DATE.match(value):
            return True
        if value is not None:
            return False
    return False


def _none(reason: str) -> dict:
    return {"type": "none", "x": None, "y": None, "reason": reason}
