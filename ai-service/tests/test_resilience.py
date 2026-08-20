"""Budget de jetons et coupe-circuit (S6-J5).

Les deux mécanismes sont **entièrement déterministes** : aucun appel réseau, aucun modèle. C'est
pour cela qu'ils tiennent en CI, alors que la démonstration de résilience (couper Groq et regarder
l'agent dégrader) restera manuelle.

Ce qui est vérifié n'est pas « ça marche » mais les trois façons dont ces garde-fous pourraient
nuire : couper trop tard (après avoir dépensé), s'ouvrir sur un incident passager, ou rester ouverts
une fois le fournisseur revenu.
"""
import pytest

from app.core import circuit
from app.core.run_context import AgentRun, BudgetExceeded, current, run_scope


@pytest.fixture(autouse=True)
def _clean_circuits():
    circuit.reset()
    yield
    circuit.reset()


# ---------------------------------------------------------------------------
# Budget
# ---------------------------------------------------------------------------


def test_budget_blocks_before_spending_not_after():
    """Le contrôle a lieu **avant** l'appel : après, ce serait de la comptabilité."""
    run = AgentRun(agent="resolution", budget_tokens=1_000)
    run.record("groq/x", prompt=600, completion=400, degraded=False)

    with pytest.raises(BudgetExceeded):
        run.check_budget()


def test_a_run_under_budget_passes():
    run = AgentRun(agent="resolution", budget_tokens=1_000)
    run.record("groq/x", prompt=300, completion=200, degraded=False)
    run.check_budget()  # ne lève pas


def test_zero_budget_means_no_limit():
    """Un budget nul désactive la borne — les harnesses d'éval tournent sans plafond."""
    run = AgentRun(agent="eval", budget_tokens=0)
    run.record("groq/x", prompt=10_000_000, completion=0, degraded=False)
    run.check_budget()


def test_degradation_is_sticky():
    """Un seul appel dégradé suffit à marquer le run : c'est ce qui permet, plus tard, de ne pas
    comparer sa qualité à celle d'un run nominal."""
    run = AgentRun(agent="insight")
    run.record("groq/x", 10, 10, degraded=True)
    run.record("groq/x", 10, 10, degraded=False)
    assert run.degraded is True


@pytest.mark.asyncio
async def test_run_scope_exposes_and_clears_the_context():
    assert current() is None
    async with run_scope("resolution", ticket_id=42, budget=500) as run:
        assert current() is run
        assert run.ticket_id == 42
    # Le contexte est rendu même en sortie normale : sinon un run fuiterait sur le suivant.
    assert current() is None


@pytest.mark.asyncio
async def test_run_scope_records_the_error_and_reraises():
    """Un journal qui n'enregistre que les succès ne sert à rien le jour où l'on cherche
    pourquoi quelque chose n'a pas marché."""
    with pytest.raises(ValueError):
        async with run_scope("digest") as run:
            raise ValueError("boom")
    assert run.error is not None
    assert "boom" in run.error
    assert current() is None


# ---------------------------------------------------------------------------
# Coupe-circuit
# ---------------------------------------------------------------------------


def test_quota_errors_are_durable():
    for message in ("Rate limit reached", "insufficient_quota", "429 Too Many Requests",
                    "Invalid API key", "401 Unauthorized"):
        assert circuit.is_durable_failure(RuntimeError(message)), message


def test_transient_errors_are_not_durable():
    """Un coupe-circuit qui compte n'importe quel échec prive du meilleur fournisseur pour rien."""
    for message in ("Read timed out", "Connection reset by peer", "500 Internal Server Error"):
        assert not circuit.is_durable_failure(RuntimeError(message)), message


def test_circuit_opens_only_after_repeated_durable_failures():
    key = "groq/llama#abc123"
    assert not circuit.is_open(key)

    circuit.record_failure(key, RuntimeError("rate limit exceeded"))
    # Un seul échec ne suffit pas : il peut être un accident mal étiqueté par le fournisseur.
    assert not circuit.is_open(key)

    circuit.record_failure(key, RuntimeError("rate limit exceeded"))
    assert circuit.is_open(key)


def test_transient_failures_never_open_the_circuit():
    key = "groq/llama#abc123"
    for _ in range(10):
        circuit.record_failure(key, RuntimeError("Read timed out"))
    assert not circuit.is_open(key)


def test_success_closes_the_circuit():
    key = "gemini/flash"
    circuit.record_failure(key, RuntimeError("quota"))
    circuit.record_failure(key, RuntimeError("quota"))
    assert circuit.is_open(key)

    circuit.record_success(key)
    assert not circuit.is_open(key)


def test_circuit_reopens_after_the_delay_and_lets_one_call_through(monkeypatch):
    """Demi-ouverture : à l'expiration, un appel passe pour tester le terrain."""
    key = "groq/llama#abc123"
    circuit.record_failure(key, RuntimeError("quota"))
    circuit.record_failure(key, RuntimeError("quota"))
    assert circuit.is_open(key)

    clock = [circuit.time.monotonic() + circuit.OPEN_SECONDS + 1]
    monkeypatch.setattr(circuit.time, "monotonic", lambda: clock[0])
    assert not circuit.is_open(key)

    # Et si l'essai échoue encore, le circuit se rouvre immédiatement — le compteur avait été
    # laissé juste sous le seuil.
    circuit.record_failure(key, RuntimeError("quota"))
    assert circuit.is_open(key)


def test_each_api_key_has_its_own_circuit():
    """Le multi-comptes Groq existe parce que les quotas sont par compte : une clé épuisée ne doit
    pas condamner les autres."""
    circuit.record_failure("groq/llama#aaaaaa", RuntimeError("quota"))
    circuit.record_failure("groq/llama#aaaaaa", RuntimeError("quota"))
    assert circuit.is_open("groq/llama#aaaaaa")
    assert not circuit.is_open("groq/llama#bbbbbb")


def test_snapshot_reports_open_circuits():
    key = "groq/llama#abc123"
    circuit.record_failure(key, RuntimeError("quota"))
    circuit.record_failure(key, RuntimeError("quota"))
    assert "ouvert" in circuit.snapshot()[key]
