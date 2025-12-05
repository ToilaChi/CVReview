from pydantic_settings import BaseSettings
from functools import lru_cache


class Settings(BaseSettings):
    # Application
    APP_NAME: str = "Embedding Service"
    VERSION: str = "1.0.0"
    DEBUG: bool = True
    
    # Embedding Model
    EMBEDDING_MODEL_NAME: str = "BAAI/bge-small-en-v1.5"  # 384 dims, good for semantic search
    # Alternative: "sentence-transformers/all-MiniLM-L6-v2" (faster, lighter)
    EMBEDDING_DIMENSION: int = 384
    BATCH_SIZE: int = 100  # Batch size for Qdrant upsert
    
    # Qdrant Cloud
    QDRANT_URL: str = ""  # Qdrant Cloud URL
    QDRANT_API_KEY: str = ""  # Qdrant Cloud API Key
    QDRANT_USE_CLOUD: bool = True  # True for cloud, False for local
    
    # Qdrant Local (fallback)
    QDRANT_HOST: str = "localhost"
    QDRANT_PORT: int = 6333
    
    # Collections
    CV_COLLECTION_NAME: str = "cv_embeddings"
    JD_COLLECTION_NAME: str = "jd_embeddings"
    
    class Config:
        env_file = ".env"
        case_sensitive = True


@lru_cache()
def get_settings() -> Settings:
    """Cache settings instance"""
    return Settings()