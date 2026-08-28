from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str = "postgresql+asyncpg://supportiq:firas@localhost:5432/supportiq"
    rabbitmq_url: str = "amqp://supportiq:firas@localhost:5672/"
    confidence_threshold: float = 0.50   # calibré en S3-J5 (ADR-0004 : escalade coûteuse, gain marginal)
    model_dir: str = "/models"          # dossier du modèle de triage (triage_xlmr.onnx + triage_tokenizer)
    embedding_model: str = "intfloat/multilingual-e5-base"   # embeddings FR+EN, 768 dims (S3-J4)
    duplicate_threshold: float = 0.92   # cosinus au-delà duquel deux tickets de même catégorie = doublons
    hnsw_ef_search: int = 400           # largeur de recherche HNSW (recall/latence) — corpus à doublons
    # --- Retrieval hybride de la base de connaissances (S5-J2) ---
    rrf_k: int = 60                     # constante de la fusion RRF (valeur de l'article de reference)
    retrieval_pool_factor: int = 4      # candidats recuperes par moteur = k x facteur, avant fusion
    # Reranking DESACTIVE par defaut apres mesure (ADR-0005) : sur ce corpus il degrade le MRR
    # (0,900 -> 0,859) pour ~17x la latence (58 ms -> 1019 ms, inference CPU d'un cross-encodeur de
    # 470 Mo), avec des defaillances brutales (rang 1 -> absent du top 5) evoquant un decalage de
    # domaine. A re-mesurer sur GPU, ou sur un corpus de quelques milliers de fragments.
    rerank_enabled: bool = False
    rerank_model: str = "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1"   # cross-encodeur multilingue FR+EN
    # --- Budgets de jetons par execution d'agent (S6-J5) ---
    # Bornes **par run**, pas par jour : elles protegent d'une boucle malheureuse sur un cas
    # particulier (prompt qui grossit a chaque reprise, modele qui part en boucle), pas de l'usage
    # normal. Calibrees genereusement — un run qui les atteint est anormal, pas simplement charge.
    budget_resolution_tokens: int = 20_000   # jusqu'a 3 generations + auto-verification
    budget_insight_tokens: int = 12_000      # jusqu'a 3 generations SQL + synthese
    budget_digest_tokens: int = 8_000        # un seul commentaire, mais un long contexte de chiffres
    budget_triage_tokens: int = 4_000        # une escalade de classification
    budget_topics_tokens: int = 15_000       # jusqu'a 20 libelles de sujets emergents (S7-J1)
    # Structuration d'un document en lot de tickets (S7-J4). Genereux : un PDF de 30 pages est
    # decoupe en plusieurs tranches, et chaque tranche renvoie du texte recopie verbatim — c'est
    # l'appel le plus consommateur du projet en jetons de sortie.
    budget_extract_tokens: int = 60_000
    # --- Sujets emergents (S7-J1) ---
    topics_window_days: int = 14             # fenetre analysee ; sa moitie sert de reference
    # --- Anomalies de volume (S7-J2) ---
    # 336 heures = 14 jours, soit 14 observations par phase horaire : assez pour estimer une forme
    # saisonniere de periode 24 sans remonter a une epoque ou le produit etait different.
    anomaly_window_hours: int = 336
    # --- Risque de depassement SLA (S7-J3) ---
    # Seuil de la file « a risque ». Ce n'est pas une propriete du modele mais une decision
    # d'exploitation : il fixe la taille de la file prioritaire et devrait se regler sur la
    # capacite de l'equipe. 0,7 est un point de depart, pas une mesure (ADR-0010).
    sla_at_risk_threshold: float = 0.70
    # --- Agent Insight, text-to-SQL (S6-J1) ---
    # Identifiants du role PostgreSQL **en lecture seule** cree par la migration V11. Deuxieme
    # barriere, independante de la validation AST : meme une requete qui passerait la garde
    # applicative ne peut ni ecrire ni lire les tables brutes.
    insight_db_user: str = "insight_ro"
    insight_db_password: str = "insight"
    insight_statement_timeout_ms: int = 5000   # rapport §11 : timeout 5 s
    insight_max_rows: int = 500
    groq_api_key: str = ""
    gemini_api_key: str = ""
    openrouter_api_key: str = ""
    ollama_base_url: str = "http://localhost:11434"
    # Observabilité LLM (S3-J5) — traces Langfuse activées seulement si les clés sont fournies.
    langfuse_public_key: str = ""
    langfuse_secret_key: str = ""
    langfuse_host: str = "http://localhost:3000"

    class Config:
        env_file = ".env"
        extra = "ignore"


settings = Settings()
