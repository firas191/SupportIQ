from datetime import date
from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field


class Priority(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class Category(str, Enum):
    TECHNIQUE = "TECHNIQUE"
    FACTURATION = "FACTURATION"
    COMPTE = "COMPTE"
    RECLAMATION = "RECLAMATION"
    DEMANDE = "DEMANDE"


class Sentiment(str, Enum):
    NEG = "NEG"
    NEU = "NEU"
    POS = "POS"


class AnalyzeRequest(BaseModel):
    ticket_id: int | None = None
    text: str = Field(min_length=1)
    language: str | None = None  # "fr" | "en" — détectée si absente


class AnalysisResult(BaseModel):
    """Contrat de sortie strict — toute réponse LLM est validée contre ce schéma."""
    priority: Priority
    category: Category
    sentiment: Sentiment
    keywords: list[str] = []
    confidence: float = Field(ge=0, le=1)
    language: str
    model_used: str
    escalated_to_llm: bool = False


class SimilarRequest(BaseModel):
    """Recherche de tickets similaires : par id (embedding déjà stocké) ou par texte libre."""
    ticket_id: int | None = None
    text: str | None = None
    k: int = Field(default=5, ge=1, le=50)


class SimilarTicket(BaseModel):
    ticket_id: int
    subject: str | None = None
    category: str | None = None
    similarity: float           # cosinus (1 = identique)
    is_duplicate: bool          # même catégorie + cosinus ≥ seuil de doublon


# --- Base de connaissances (S5-J1) ------------------------------------------


class KbDocument(BaseModel):
    """Un document indexé, vu depuis l'écran d'administration (agrégat de ses fragments)."""

    source: str
    title: str
    chunks: int
    indexed: int
    updated_at: str | None = None


class KbIngestResult(BaseModel):
    source: str
    title: str
    chunks: int
    indexed: int
    characters: int


class KbSearchRequest(BaseModel):
    question: str = Field(min_length=2, max_length=1000)
    k: int = Field(default=5, ge=1, le=20)
    # « vector » = comportement du S5-J1 (vecteurs seuls), conserve comme point de comparaison
    # chiffre ; « hybrid » = BM25 + vecteurs, fusion RRF, reranking cross-encodeur (S5-J2).
    mode: Literal["vector", "hybrid"] = "hybrid"


class KbChunk(BaseModel):
    """Fragment retrouvé. `heading` et `source` permettent de citer précisément (S5-J3/J4)."""

    id: int
    title: str
    source: str
    chunk_index: int
    heading: str | None = None
    content: str
    similarity: float


class KbReindexResult(BaseModel):
    processed: int
    failed: int


# --- Agent Resolution (S5-J3) -----------------------------------------------


class ResolutionRequest(BaseModel):
    """Contrat du rapport §6 : POST /agents/resolution {ticket_id} -> DraftResponse."""

    ticket_id: int
    # Ton configurable (rapport §5.2). Deux registres suffisent : le formel couvre le support
    # standard, l'empathique les reclamations et les clients mecontents.
    tone: Literal["formal", "empathetic"] = "formal"


class Citation(BaseModel):
    """Source d'une affirmation du brouillon.

    `chunk_id` + `source` + `heading` permettront le surlignage du passage exact dans l'ecran de la
    base de connaissances (S5-J4) ; `excerpt` evite a l'agent de changer d'ecran pour verifier.
    """

    marker: int
    chunk_id: int | None = None
    source: str | None = None
    heading: str | None = None
    excerpt: str = ""


class DraftResponse(BaseModel):
    """Brouillon propose. **Jamais envoye automatiquement** : un humain valide (rapport §5.2)."""

    draft_id: int | None = None
    ticket_id: int
    content: str
    citations: list[Citation] = []
    tone: str = "formal"
    # Vrai quand l'auto-verification n'a pas converge : l'interface doit avertir avant lecture.
    low_confidence: bool = False
    # Le modele a explicitement reconnu que la documentation ne couvre pas la demande. C'est un
    # **resultat correct**, pas un echec : l'interface affiche « rien a proposer » et non une
    # alerte. Distinguer les deux evite les fausses alarmes, qui apprennent a ignorer les vraies.
    abstained: bool = False
    issues: list[str] = []
    attempts: int = 0
    passages_used: int = 0


# --- Agent Insight, text-to-SQL (S6-J1) --------------------------------------


class InsightRequest(BaseModel):
    """Contrat du rapport §6 : POST /agents/insight {question, user_role}.

    `user_role` est accepte pour respecter le contrat mais **n'est pas une autorisation** : le RBAC
    est applique par Spring (MANAGER+) avant que cet appel n'existe. Un service interne qui se
    fierait a un role transmis dans un corps JSON n'aurait aucune securite du tout.
    """

    question: str
    user_role: str | None = None


class DigestRequest(BaseModel):
    """Contrat du rapport §6 : POST /agents/digest {week} -> DigestReport.

    `week_start` est le **lundi** de la semaine voulue. Absent = la semaine ecoulee, ce qui est le
    cas d'usage de la planification du lundi matin.
    """

    week_start: date | None = None


class DigestReport(BaseModel):
    """Synthese hebdomadaire. Le PDF voyage en base64 dans la reponse.

    **Pourquoi pas un volume partage** entre les deux conteneurs : un fichier ephemere de quelques
    centaines de kilo-octets ne justifie pas une dependance de deploiement qu'il faudrait recreer
    dans chaque environnement. Le surcout du base64 (+33 %) est negligeable a cette taille.
    """

    week_start: date
    markdown: str
    stats: dict = {}
    # `None` quand le rendu PDF n'est pas disponible : le digest reste envoyable en texte.
    pdf_base64: str | None = None


class ChartSpec(BaseModel):
    """Graphique a tracer, **deduit du resultat par le code** (S6-J2, `app/agents/chart.py`).

    `type = "none"` est une valeur normale, pas une absence : `reason` dit pourquoi, ce qui permet
    a l'interface d'ecrire « une seule valeur, pas de graphique » au lieu d'afficher un cadre vide.
    """

    type: Literal["bar", "line", "none"] = "none"
    x: str | None = None
    y: str | None = None
    reason: str = ""


class InsightResponse(BaseModel):
    """Resultat d'une question de manager (contrat §6 : {answer, sql, chart_spec})."""

    question: str
    # Le SQL est renvoye volontairement : c'est le « mode transparent » du rapport §9 (S6-J3).
    # Montrer la requete est ce qui permet a un manager de ne pas croire un chiffre sur parole.
    sql: str
    # Synthese en langage naturel. Vide si le modele etait indisponible : les lignes restent
    # exploitables, on ne fait pas echouer une requete reussie pour une mise en mots manquante.
    answer: str = ""
    chart: ChartSpec = ChartSpec()
    columns: list[str] = []
    rows: list[list] = []
    row_count: int = 0
    # Nombre de generations. > 1 signifie que la boucle de reparation a corrige une erreur SQL.
    attempts: int = 0
    # Le plafond de lignes a probablement tronque : sans ce drapeau, un manager lirait « 500 »
    # la ou il y en a 12 000.
    truncated: bool = False


class TopicsDetectRequest(BaseModel):
    """Declenche un instantane de sujets emergents (S7-J1).

    `window_days` absent = la valeur de configuration. Le parametre existe pour la demonstration
    et le rattrapage, pas pour l'usage courant : changer la fenetre change le sens de la croissance
    (elle compare les deux moities de la fenetre), donc deux instantanes de fenetres differentes ne
    se comparent pas.
    """

    window_days: int | None = None


class TopicsDetectResult(BaseModel):
    """Compte rendu d'une detection. Les sujets eux-memes sont lus en base par Spring.

    `analysed` est volontairement renvoye a cote de `topics` : zero sujet sur 12 tickets analyses
    n'a pas le meme sens que zero sujet sur 4 000. Sans ce chiffre, une detection vide serait
    indiscernable d'une panne d'embeddings.
    """

    window_days: int
    analysed: int
    topics: int


class AnomalyDetectRequest(BaseModel):
    """Declenche une mesure d'anomalie de volume (S7-J2).

    `lookback` = nombre d'heures a juger. 1 en fonctionnement normal (le detecteur tourne toutes les
    heures et n'a que la derniere a examiner) ; une valeur plus grande sert au rattrapage apres un
    arret, et a la demonstration quand le pic vient d'etre injecte.
    """

    window_hours: int | None = None
    lookback: int = 1


class AnomalyCandidate(BaseModel):
    """Un pic constate. **Candidate**, pas alerte : c'est Spring qui decide d'en creer une.

    `expected` et `observed` voyagent a cote du score parce qu'un score seul n'est pas lisible : « 7,2 »
    ne dit rien, « 41 tickets la ou 6 etaient attendus » se comprend sans connaitre la methode.
    """

    scope: str
    bucket_start: str
    severity: str
    observed: int
    expected: float
    score: float
    # `stl` ou `seasonal_median` : un chiffre obtenu par le repli ne se compare pas a un chiffre
    # obtenu par la decomposition complete. Meme principe que `judged_by` au S5-J5.
    method: str
    payload: dict = {}


class FieldConfidence(BaseModel):
    """Confiance **par champ** (S7-J4, rapport §5.4).

    Bien plus utile qu'un score global : en pratique le sujet et le corps sont presque toujours
    bons, et c'est l'adresse du client qui manque ou qui est mal recopiee. « 0,7 » ne dit pas quoi
    relire ; « adresse : 0,3 » le dit.
    """

    subject: float = 0.0
    body: float = 0.0
    customer_email: float = 0.0


class ExtractedTicket(BaseModel):
    """Une demande client isolee dans un document non structure.

    **Proposee, jamais inseree.** L'ecran de validation la fait relire avant creation — meme
    architecture que le brouillon de reponse (S5-J4), et pour la meme raison : un decoupage errone
    creerait des tickets fantomes que personne ne verrait passer.
    """

    subject: str
    body: str
    customer_email: str | None = None
    language: str | None = None
    confidence: FieldConfidence = FieldConfidence()


class TicketBatch(BaseModel):
    """Lot extrait d'un document (contrat §6 : POST /extract -> TicketBatch)."""

    tickets: list[ExtractedTicket] = []
    pages: int = 0
    # `native`, `ocr` ou `plain`. Un texte issu d'OCR merite une relecture plus attentive, et
    # l'agent doit savoir lequel il relit.
    method: str = "native"


class SlaScoreResult(BaseModel):
    """Compte rendu d'un recalcul du risque SLA (S7-J3).

    `model` vaut `lightgbm` ou `rules`. Il est remonte pour la meme raison qu'au S5-J5 (`judged_by`)
    et au S7-J2 (`method`) : un chiffre produit par la regle de repli ne se compare pas a un chiffre
    produit par le modele entraine, et rien d'autre ne permettrait de les distinguer apres coup.
    """

    scored: int
    model: str
    at_risk: int


class AnomalyDetectResult(BaseModel):
    window_hours: int
    # Nombre de categories examinees : zero anomalie sur 2 categories ne veut pas dire la meme
    # chose que zero sur 6. Meme motif qu'au S7-J1 avec `analysed`.
    categories: int
    anomalies: list[AnomalyCandidate] = []
