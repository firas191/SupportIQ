"""Ingestion de documents non structurés (S7-J4).

La structuration dépend d'un modèle : sa **qualité** ne se teste pas ici. Ce qui se teste, et qui
compte davantage, c'est ce que le code fait de sa réponse — parce que c'est là que se joue la seule
question qui importe : **peut-on créer un ticket que le document ne contenait pas ?**

Le contrôle d'ancrage (`_is_grounded`) est la réponse, et il est déterministe, donc testable.
"""
import pytest

from app.extract import structure

CHUNK = (
    "Bonjour, ma commande 48219 n'est jamais arrivee malgre le suivi qui indique "
    "une livraison. Merci de me dire ce qui s'est passe.\n\n"
    "Hello, I was charged twice for order 77120 on March 3rd. Please refund the "
    "duplicate payment. Contact: alice@example.com"
)


def payload(*tickets) -> str:
    import json

    return json.dumps({"tickets": list(tickets)})


def entry(body: str, subject: str = "Demande", **extra) -> dict:
    return {"subject": subject, "body": body, "language": "fr", **extra}


# ---------------------------------------------------------------------------
# Ancrage : la seule protection contre l'invention
# ---------------------------------------------------------------------------


def test_a_ticket_quoted_from_the_document_is_kept():
    raw = payload(entry("ma commande 48219 n'est jamais arrivee malgre le suivi"))
    assert len(structure._parse(raw, CHUNK)) == 1


def test_a_fabricated_ticket_is_rejected_not_repaired():
    """Un ticket inventé ressemble en tout point à un ticket correct.

    C'est ce qui rend l'invention dangereuse ici, et pourquoi le rejet est franc : on n'essaie pas
    de « redresser » une entrée dont le corps ne vient pas du document.
    """
    raw = payload(entry("Je souhaite resilier mon abonnement fibre depuis janvier dernier"))
    assert structure._parse(raw, CHUNK) == []


def test_grounding_survives_reencoded_punctuation_and_accents():
    """Le modèle réencode volontiers apostrophes, espaces, retours à la ligne — **et accents**.

    Le cas des accents n'est pas théorique : un PDF scanné rend « arrivee », et le modèle écrit
    « arrivée » en recopiant. Comparer littéralement rejetterait cette entrée comme inventée, ce
    qui est le faux négatif le plus difficile à diagnostiquer — le texte affiché serait
    visiblement correct.
    """
    # `chr(0x2019)` et non le caractere litteral : ruff signale a juste titre les apostrophes
    # typographiques dans du code, et surtout elles sont **indiscernables** d'une apostrophe droite
    # a la relecture — or c'est precisement la difference que ce test exerce. Meme procede qu'au
    # S6-J4 pour l'espace fine.
    apostrophe = chr(0x2019)
    reencoded = f"ma commande 48219 n{apostrophe}est   jamais\narrivée malgré le suivi"
    assert len(structure._parse(payload(entry(reencoded)), CHUNK)) == 1


def test_an_entry_without_a_body_is_rejected():
    raw = payload({"subject": "Demande", "language": "fr"})
    assert structure._parse(raw, CHUNK) == []


# ---------------------------------------------------------------------------
# Tolérance de forme
# ---------------------------------------------------------------------------


def test_a_json_wrapped_in_a_code_fence_is_accepted():
    """Strict sur le fond, tolérant sur la forme : un modèle enrobe volontiers sa réponse."""
    raw = "Voici le resultat :\n```json\n" + payload(
        entry("I was charged twice for order 77120 on March 3rd")
    ) + "\n```"
    assert len(structure._parse(raw, CHUNK)) == 1


def test_an_unparseable_answer_yields_nothing_rather_than_an_error():
    assert structure._parse("je n'ai pas compris la demande", CHUNK) == []
    assert structure._parse("", CHUNK) == []


# ---------------------------------------------------------------------------
# Adresse dérivée
# ---------------------------------------------------------------------------


def test_an_email_inside_the_request_itself_is_recovered():
    """Le corps est verbatim : une adresse qui s'y trouve appartient bien à cette demande."""
    body = "Contact: alice@example.com"
    ticket = structure._parse(payload(entry(body)), CHUNK)[0]

    assert ticket["customer_email"] == "alice@example.com"


def test_a_lone_email_in_the_document_is_borrowed_with_low_confidence():
    """Une seule adresse dans tout le document : l'attribution est probable, pas certaine."""
    single = "Bonjour, ma commande 48219 n'est jamais arrivee. Contact: solo@example.com"
    ticket = structure._parse(
        payload(entry("ma commande 48219 n'est jamais arrivee")), single
    )[0]

    assert ticket["customer_email"] == "solo@example.com"
    assert ticket["confidence"]["customer_email"] <= 0.5


def test_no_email_is_invented_when_the_document_holds_several():
    """**Le défaut trouvé à la première utilisation réelle.**

    Sur un document de trois demandes dont deux portent une adresse, la troisième héritait de
    celle de la première : une réponse sur un problème de connexion serait partie chez la cliente
    qui signalait un colis perdu.

    Le surlignage « à vérifier » ne suffit pas — c'est une mitigation visuelle, qui dépend de
    l'attention de l'agent. Quand l'attribution est ambiguë, on laisse vide : visible et
    corrigeable vaut mieux que faux et plausible.
    """
    ambiguous = (
        "Demande 1\nDe : alice@example.com\nma commande 48219 n'est jamais arrivee.\n\n"
        "Demande 2\nDe : bob@example.com\nI was charged twice for order 77120.\n\n"
        "Demande 3\nJe n'arrive plus a me connecter a mon compte."
    )
    ticket = structure._parse(
        payload(entry("Je n'arrive plus a me connecter a mon compte")), ambiguous
    )[0]

    assert ticket["customer_email"] is None


def test_an_email_given_by_the_model_is_left_alone():
    raw = payload(
        entry("ma commande 48219 n'est jamais arrivee", customer_email="bob@example.com")
    )
    assert structure._parse(raw, CHUNK)[0]["customer_email"] == "bob@example.com"


# ---------------------------------------------------------------------------
# Découpage et doublons
# ---------------------------------------------------------------------------


def test_a_short_document_is_a_single_chunk():
    assert structure._chunks("court") == ["court"]


def test_chunks_overlap_so_a_request_astride_two_is_complete_in_one():
    text = "x" * (structure.CHUNK_CHARS * 2)
    chunks = structure._chunks(text)

    assert len(chunks) >= 2
    # Le recouvrement existe pour qu'une demande a cheval soit entiere quelque part ; les doublons
    # qu'il produit sont elimines ensuite.
    assert chunks[1].startswith(chunks[0][-structure.CHUNK_OVERLAP:])


def test_duplicates_born_from_the_overlap_are_removed():
    same = {"subject": "A", "body": "ma commande 48219 n'est jamais arrivee", "confidence": {}}
    assert len(structure._dedupe([same, dict(same), {"subject": "B", "body": "autre chose"}])) == 2


def test_the_batch_is_capped():
    many = [{"subject": f"S{i}", "body": f"corps different numero {i}"} for i in range(200)]
    assert len(structure._dedupe(many)[: structure.MAX_TICKETS]) == structure.MAX_TICKETS


# ---------------------------------------------------------------------------
# Extraction : formats refusés
# ---------------------------------------------------------------------------


def test_an_unknown_format_is_refused_with_a_useful_message():
    from app.extract import documents
    from app.kb.loader import UnsupportedDocument

    with pytest.raises(UnsupportedDocument) as error:
        documents.extract("archive.zip", b"PK\x03\x04")

    assert ".pdf" in str(error.value)


def test_plain_text_is_read_without_any_heavy_dependency():
    from app.extract import documents

    result = documents.extract("demandes.txt", "Bonjour,\n\n\n\nma commande.".encode())

    assert result.method == "plain"
    # La normalisation du chargeur KB est reutilisee : trois sauts de ligne ou plus deviennent une
    # seule separation de paragraphe.
    assert "\n\n\n" not in result.text
