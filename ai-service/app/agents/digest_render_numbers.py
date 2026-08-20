"""Formatage typographique français des nombres du digest (S6-J4).

Isolé dans son propre module pour une raison précise : c'est le seul endroit du dépôt qui manipule
un caractère Unicode **invisible**. Le regrouper ici le rend facile à trouver, au lieu de le
disperser dans du code métier où personne ne le verrait.

Le point de code est construit par `chr()` plutôt qu'écrit en clair. Une espace fine insécable est
indistinguable d'une espace ordinaire dans un éditeur : le jour où quelqu'un « corrige »
l'alignement, il la remplace sans s'en apercevoir, et le rendu change en silence. `chr(0x202F)`
énonce l'intention, garde le fichier en ASCII pur, et lève au passage l'avertissement RUF001 — qui
signalait à juste titre cette ambiguïté.
"""
from __future__ import annotations

#: Espace fine insécable (U+202F) — séparateur de milliers prescrit en français.
NARROW_NBSP = chr(0x202F)


def fr_number(value: float, decimals: int = 0) -> str:
    """Nombre à la française : virgule décimale, espace fine insécable par millier.

    `0.0 %` et `10014` sont deux fautes dans un document français, et la seconde coûte en plus un
    effort de lecture — on compte les chiffres pour situer l'ordre de grandeur.
    """
    if decimals:
        return f"{value:,.{decimals}f}".replace(",", NARROW_NBSP).replace(".", ",")
    # `round` sur un flottant, sans second argument, renvoie deja un entier : l'envelopper dans
    # `int()` ne fait rien (RUF046).
    return f"{round(value):,}".replace(",", NARROW_NBSP)


def signed(value: float, decimals: int = 0) -> str:
    """Nombre precede de son signe : `+3`, `-2,4`.

    Le signe moins est un **trait d'union-moins ASCII** et non le moins typographique U+2212.
    Ce dernier serait plus juste en composition, mais il est visuellement identique dans le code
    source et introduit une ambiguite pour un gain que personne ne remarquera dans un tableau
    Markdown. On ne paie pas une ambiguite permanente pour un raffinement invisible.
    """
    sign = "+" if value >= 0 else "-"
    return f"{sign}{fr_number(abs(value), decimals)}"


def plural(count: int, singular: str, many: str | None = None) -> str:
    """Accord singulier/pluriel. « 1 tickets » saute aux yeux dans un document envoyé."""
    return singular if abs(count) < 2 else (many or singular + "s")
