"""Coupe-circuit par fournisseur LLM (S6-J5, rapport §9).

**Le problème.** La chaîne de repli essaie les fournisseurs dans l'ordre. Quand le quota Groq est
épuisé — ce qui arrive tous les jours en fin de campagne d'évaluation — *chaque* appel repaie le
prix de l'échec : une tentative par clé, chacune avec son aller-retour réseau et son délai
d'expiration. Avec trois clés, c'est trois échecs garantis avant d'atteindre le premier fournisseur
capable de répondre. Multiplié par cent appels, la latence devient le vrai problème, bien avant la
qualité.

**Le principe.** Après quelques échecs *de la même nature*, on cesse d'essayer ce fournisseur
pendant un temps, et on passe directement au suivant. Le circuit se referme tout seul : à
l'expiration du délai, un appel est laissé passer pour tester le terrain.

**La distinction qui fait tout.** Un coupe-circuit qui compte n'importe quel échec s'ouvre au
premier hoquet réseau et prive de son meilleur fournisseur pour rien. Ici, seuls les échecs
**durables** comptent : quota épuisé, clé invalide, authentification refusée. Un timeout isolé ou
une erreur 500 passagère ne fait pas monter le compteur — ils se reproduiront de toute façon si le
problème persiste, et alors le fournisseur suivant prendra le relais sans qu'on ait besoin de
mémoriser quoi que ce soit.

L'état est **en mémoire, par processus**. En multi-instance, chaque nœud découvre l'épuisement de
son côté ; c'est acceptable, le coût de la découverte est de quelques appels par nœud. Un état
partagé (Redis) ajouterait une dépendance pour économiser trois requêtes.
"""
from __future__ import annotations

import logging
import re
import time

logger = logging.getLogger(__name__)

#: Échecs durables consécutifs avant ouverture. Deux, et non un : une erreur isolée peut être un
#: accident de réseau mal étiqueté par le fournisseur.
FAILURE_THRESHOLD = 2

#: Durée d'ouverture. Un quota Groq se réinitialise à l'heure ou à la journée ; cinq minutes est un
#: compromis — assez long pour éviter de marteler, assez court pour retrouver le bon fournisseur
#: sans redémarrer le service.
OPEN_SECONDS = 300

#: Motifs d'échec **durable**. On lit le message parce que litellm normalise mal les codes d'erreur
#: entre fournisseurs — le texte est ce qu'il y a de plus stable.
_DURABLE = re.compile(
    r"rate.?limit|quota|insufficient|too many requests|429"
    r"|unauthor|forbidden|invalid.{0,10}api.?key|401|403",
    re.IGNORECASE,
)


def is_durable_failure(error: BaseException) -> bool:
    """Vrai pour un épuisement de quota ou une clé refusée — pas pour un timeout."""
    return bool(_DURABLE.search(str(error)))


class _Breaker:
    def __init__(self) -> None:
        self.failures = 0
        self.opened_at: float | None = None


_breakers: dict[str, _Breaker] = {}


def _breaker(key: str) -> _Breaker:
    return _breakers.setdefault(key, _Breaker())


def is_open(key: str) -> bool:
    """Le fournisseur doit-il être sauté ? Referme le circuit quand le délai est écoulé."""
    breaker = _breaker(key)
    if breaker.opened_at is None:
        return False
    if time.monotonic() - breaker.opened_at >= OPEN_SECONDS:
        # Demi-ouverture : on laisse passer l'appel suivant. S'il échoue durablement, le compteur
        # repart et le circuit se rouvre immédiatement (seuil déjà atteint).
        logger.info("Circuit %s referme apres %ds, nouvel essai", key, OPEN_SECONDS)
        breaker.opened_at = None
        breaker.failures = FAILURE_THRESHOLD - 1
        return False
    return True


def record_success(key: str) -> None:
    """Un succès efface l'ardoise : le fournisseur est revenu."""
    breaker = _breaker(key)
    if breaker.failures or breaker.opened_at:
        logger.info("Circuit %s retabli", key)
    breaker.failures = 0
    breaker.opened_at = None


def record_failure(key: str, error: BaseException) -> None:
    """Compte un échec **durable**. Les autres sont ignorés volontairement."""
    if not is_durable_failure(error):
        return
    breaker = _breaker(key)
    breaker.failures += 1
    if breaker.failures >= FAILURE_THRESHOLD and breaker.opened_at is None:
        breaker.opened_at = time.monotonic()
        logger.warning(
            "Circuit %s ouvert pour %ds apres %d echecs durables (%s)",
            key, OPEN_SECONDS, breaker.failures, str(error)[:120],
        )


def snapshot() -> dict[str, str]:
    """État lisible des circuits, pour la sonde de disponibilité.

    Sans cela, une dégradation est invisible tant que personne ne lit les journaux — et une
    dégradation invisible est une dégradation permanente.
    """
    now = time.monotonic()
    return {
        key: (
            f"ouvert ({int(OPEN_SECONDS - (now - breaker.opened_at))}s restantes)"
            if breaker.opened_at is not None
            else "ferme"
        )
        for key, breaker in _breakers.items()
    }


def reset() -> None:
    """Remise à zéro — réservée aux tests."""
    _breakers.clear()
