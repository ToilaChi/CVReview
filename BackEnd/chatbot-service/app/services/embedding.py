from sentence_transformers import SentenceTransformer
from typing import List, Optional
import numpy as np
from app.config import get_settings

settings = get_settings()


class EmbeddingService:
    """Singleton service for text embedding with lazy loading"""
    
    _instance: Optional['EmbeddingService'] = None
    _model: Optional[SentenceTransformer] = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance
    
    def _load_model(self):
        """Lazy load the embedding model"""
        if self._model is None:
            print(f"Loading embedding model: {settings.EMBEDDING_MODEL_NAME}...")
            self._model = SentenceTransformer(settings.EMBEDDING_MODEL_NAME)
            print(f"Model loaded successfully! Dimension: {settings.EMBEDDING_DIMENSION}")
    
    def embed_text(self, text: str) -> List[float]:
        """
        Embed a single text
        
        Args:
            text: Text to embed
            
        Returns:
            List of floats representing the embedding vector
        """
        self._load_model()
        embedding = self._model.encode(text, convert_to_numpy=True)
        return embedding.tolist()
    
    def embed_batch(self, texts: List[str], show_progress: bool = False) -> List[List[float]]:
        """
        Embed a batch of texts
        
        Args:
            texts: List of texts to embed
            show_progress: Show progress bar
            
        Returns:
            List of embedding vectors
        """
        self._load_model()
        embeddings = self._model.encode(
            texts,
            convert_to_numpy=True,
            show_progress_bar=show_progress,
            batch_size=32  # Internal batch size for model inference
        )
        return embeddings.tolist()
    
    def get_dimension(self) -> int:
        """Get embedding dimension"""
        return settings.EMBEDDING_DIMENSION
    
    def is_model_loaded(self) -> bool:
        """Check if model is loaded"""
        return self._model is not None


# Global instance
embedding_service = EmbeddingService()