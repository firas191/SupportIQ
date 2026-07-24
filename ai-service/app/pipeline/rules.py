"""Dérivation de la priorité par règles (ADR-0003).

Les baselines *et* le fine-tuning ont montré que la priorité n'est pas apprenable du texte de
surface (macro-F1 ~0,35 ≈ hasard). On la dérive donc par des règles explicites et auditables
plutôt que par un modèle médiocre : mots-clés d'urgence, puis heuristique catégorie/sentiment.
"""
import re

from app.schemas import Category, Priority, Sentiment

# Signaux d'urgence explicites (FR + EN). Priorité HIGH dès qu'un est présent.
_URGENT = re.compile(
    r"(urgent|urgence|imm[ée]diat|asap|au plus vite|bloqu[ée]|impossible|"
    r"en panne|\bpanne\b|\bdown\b|critique|critical|hors service|"
    r"ne (?:fonctionne|marche|répond) plus|double pr[ée]l[èe]vement|"
    r"d[ée]bit[ée] deux fois|fraude|pirat|hack|vol[ée]?|perdu de l'argent)",
    re.IGNORECASE,
)

# Signaux de faible urgence (question, remerciement, demande d'info).
_LOW = re.compile(
    r"(merci|thanks?|remerci|\bquestion\b|renseignement|information|comment (?:faire|puis)|"
    r"how (?:do|to|can)|est-ce que|disponib|availab|f[ée]licit)",
    re.IGNORECASE,
)


def derive_priority(text: str, category: Category, sentiment: Sentiment) -> Priority:
    body = text or ""
    if _URGENT.search(body):
        return Priority.HIGH
    # Un mécontentement sur la facturation ou un incident technique tend vers l'urgent.
    if sentiment == Sentiment.NEG and category in (Category.FACTURATION, Category.TECHNIQUE):
        return Priority.HIGH
    # Client satisfait, ou simple demande d'information : basse priorité.
    if sentiment == Sentiment.POS or (category == Category.DEMANDE and _LOW.search(body)):
        return Priority.LOW
    return Priority.MEDIUM
