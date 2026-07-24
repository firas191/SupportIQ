from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str = "postgresql+asyncpg://supportiq:firas@localhost:5432/supportiq"
    rabbitmq_url: str = "amqp://supportiq:firas@localhost:5672/"
    confidence_threshold: float = 0.50   # calibré en S3-J5 (ADR-0004 : escalade coûteuse, gain marginal)
    model_dir: str = "/models"          # dossier du modèle de triage (triage_xlmr.onnx + triage_tokenizer)
    embedding_model: str = "intfloat/multilingual-e5-base"   # embeddings FR+EN, 768 dims (S3-J4)
    duplicate_threshold: float = 0.92   # cosinus au-delà duquel deux tickets de même catégorie = doublons
    hnsw_ef_search: int = 400           # largeur de recherche HNSW (recall/latence) — corpus à doublons
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
