from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str = "postgresql+asyncpg://supportiq:firas@localhost:5432/supportiq"
    rabbitmq_url: str = "amqp://supportiq:firas@localhost:5672/"
    confidence_threshold: float = 0.80
    groq_api_key: str = ""
    gemini_api_key: str = ""
    openrouter_api_key: str = ""
    ollama_base_url: str = "http://localhost:11434"

    class Config:
        env_file = ".env"
        extra = "ignore"


settings = Settings()
