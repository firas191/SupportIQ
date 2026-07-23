"""Règles de priorité (ADR-0003) — logique pure, sans dépendance."""
from app.pipeline.rules import derive_priority
from app.schemas import Category, Priority, Sentiment


def test_urgent_keyword_is_high():
    assert derive_priority("Le site est en panne, c'est urgent !",
                           Category.TECHNIQUE, Sentiment.NEG) == Priority.HIGH


def test_negative_billing_is_high():
    assert derive_priority("Je conteste ce montant débité.",
                           Category.FACTURATION, Sentiment.NEG) == Priority.HIGH


def test_positive_is_low():
    assert derive_priority("Merci beaucoup pour votre aide rapide !",
                           Category.DEMANDE, Sentiment.POS) == Priority.LOW


def test_info_request_is_low():
    assert derive_priority("Quand la collection sera-t-elle disponible ?",
                           Category.DEMANDE, Sentiment.NEU) == Priority.LOW


def test_default_is_medium():
    assert derive_priority("Je voudrais modifier mon adresse de livraison.",
                           Category.COMPTE, Sentiment.NEU) == Priority.MEDIUM
