"""Contexte d'exécution d'un agent : budget de jetons et traçabilité (S6-J5, rapport §9).

**Le problème que ça résout.** Jusqu'ici, un agent pouvait dépenser sans borne : l'agent Résolution
enchaîne jusqu'à trois générations plus une auto-vérification, l'agent Insight jusqu'à trois
générations plus une synthèse. Rien n'empêchait une boucle malheureuse — un prompt qui grossit à
chaque reprise, un modèle qui répond n'importe quoi — de consommer le budget d'une journée sur un
seul ticket. Et rien ne permettait de le constater après coup.

**Pourquoi `contextvars` et non un paramètre.** La solution évidente serait de passer un objet
`budget` à chaque fonction. Elle contamine toutes les signatures jusqu'à la passerelle LLM, y
compris celles qui n'ont rien à voir avec le sujet. `contextvars` porte la valeur *le long de la
pile d'appels asynchrone* — chaque tâche `asyncio` hérite du contexte de son parent et ne voit pas
celui des autres. C'est exactement la sémantique voulue : deux tickets analysés en parallèle ont
chacun leur budget, sans que personne n'ait à transmettre quoi que ce soit.

**Le budget coupe avant de dépenser, pas après.** Vérifier après l'appel serait une comptabilité,
pas une limite. Le contrôle a lieu *avant* chaque requête : si le run a déjà consommé son budget,
l'appel n'est pas émis.
"""
from __future__ import annotations

import contextvars
import logging
import time
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


class BudgetExceeded(RuntimeError):
    """Le run a épuisé son budget de jetons. Levée **avant** l'appel, jamais après."""


@dataclass
class AgentRun:
    """État d'une exécution d'agent. Un objet par run, porté par le contexte."""

    agent: str
    ticket_id: int | None = None
    budget_tokens: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    calls: int = 0
    started_at: float = field(default_factory=time.monotonic)
    #: Dernier modèle ayant réellement répondu — le repli en fait un champ non trivial.
    model_used: str | None = None
    #: Vrai dès qu'un appel a dû descendre dans la chaîne de repli.
    degraded: bool = False
    error: str | None = None

    @property
    def total_tokens(self) -> int:
        return self.prompt_tokens + self.completion_tokens

    @property
    def duration_ms(self) -> int:
        return int((time.monotonic() - self.started_at) * 1000)

    def check_budget(self) -> None:
        """Refuse un appel supplémentaire quand le budget est consommé."""
        if self.budget_tokens and self.total_tokens >= self.budget_tokens:
            raise BudgetExceeded(
                f"Budget de {self.budget_tokens} jetons epuise "
                f"({self.total_tokens} consommes en {self.calls} appels)"
            )

    def record(self, model: str, prompt: int, completion: int, degraded: bool) -> None:
        self.calls += 1
        self.prompt_tokens += prompt
        self.completion_tokens += completion
        self.model_used = model
        # Une seule dégradation suffit à marquer le run : c'est l'information utile pour
        # interpréter sa qualité après coup.
        self.degraded = self.degraded or degraded


_current: contextvars.ContextVar[AgentRun | None] = contextvars.ContextVar(
    "supportiq_agent_run", default=None
)


def current() -> AgentRun | None:
    """Run courant, ou `None` hors de tout contexte d'agent.

    Renvoyer `None` plutôt que lever : la passerelle LLM est aussi appelée depuis les harnesses
    d'évaluation et depuis des scripts. Exiger un contexte y ajouterait une cérémonie sans objet,
    et rendrait la passerelle inutilisable hors des agents.
    """
    return _current.get()


class run_scope:
    """Gestionnaire de contexte ouvrant un run d'agent.

    Usage : `async with run_scope("resolution", ticket_id=42, budget=20_000) as run: ...`

    Le run est **toujours** refermé, y compris sur exception : c'est ce qui garantit qu'un échec
    laisse une trace. Un journal qui n'enregistre que les succès ne sert à rien le jour où l'on
    cherche pourquoi quelque chose n'a pas marché.
    """

    def __init__(self, agent: str, ticket_id: int | None = None, budget: int = 0):
        self.run = AgentRun(agent=agent, ticket_id=ticket_id, budget_tokens=budget)
        self._token: contextvars.Token | None = None

    async def __aenter__(self) -> AgentRun:
        self._token = _current.set(self.run)
        return self.run

    async def __aexit__(self, exc_type, exc, tb) -> bool:
        if exc is not None:
            self.run.error = f"{exc_type.__name__}: {exc}"[:400]

        logger.info(
            "Run %s termine: %d appels, %d jetons, %d ms, modele=%s%s",
            self.run.agent, self.run.calls, self.run.total_tokens, self.run.duration_ms,
            self.run.model_used, " (degrade)" if self.run.degraded else "",
        )

        # Persistance best-effort : la trace ne doit jamais faire échouer le travail qu'elle
        # observe. Un journal qui casse la production est pire que pas de journal.
        try:
            from app.core import agent_runs

            await agent_runs.save(self.run)
        except Exception as exc_save:  # noqa: BLE001
            logger.warning("Journalisation du run echouee: %s", exc_save)

        if self._token is not None:
            _current.reset(self._token)
        # `False` : on ne masque jamais l'exception d'origine.
        return False
