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
