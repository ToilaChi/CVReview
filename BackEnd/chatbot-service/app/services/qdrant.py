from qdrant_client import QdrantClient
from qdrant_client.models import Distance, VectorParams, PointStruct, Filter, FieldCondition, MatchValue
from typing import Optional, List, Dict, Any
from datetime import datetime
from app.config import get_settings

settings = get_settings()


class QdrantService:
    """Singleton service for Qdrant operations"""
    
    _instance: Optional['QdrantService'] = None
    _client: Optional[QdrantClient] = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance
    
    def _get_client(self) -> QdrantClient:
        """Get or create Qdrant client"""
        if self._client is None:
            if settings.QDRANT_USE_CLOUD:
                # Qdrant Cloud
                print(f"Connecting to Qdrant Cloud: {settings.QDRANT_URL}")
                self._client = QdrantClient(
                    url=settings.QDRANT_URL,
                    api_key=settings.QDRANT_API_KEY,
                    timeout=60
                )
            else:
                # Local Qdrant
                print(f"Connecting to Qdrant Local: {settings.QDRANT_HOST}:{settings.QDRANT_PORT}")
                self._client = QdrantClient(
                    host=settings.QDRANT_HOST,
                    port=settings.QDRANT_PORT,
                    timeout=60
                )
            print("Qdrant client connected successfully!")
        return self._client
    
    def test_connection(self) -> bool:
        """Test Qdrant connection"""
        try:
            client = self._get_client()
            collections = client.get_collections()
            print(f"Qdrant connection OK. Collections: {len(collections.collections)}")
            return True
        except Exception as e:
            print(f"Qdrant connection failed: {e}")
            return False
    
    def create_collection(self, collection_name: str, vector_size: int):
        """Create a collection if not exists"""
        client = self._get_client()
        
        # Check if collection exists
        collections = client.get_collections().collections
        if any(col.name == collection_name for col in collections):
            print(f"Collection '{collection_name}' already exists")
            return
        
        # Create collection
        client.create_collection(
            collection_name=collection_name,
            vectors_config=VectorParams(
                size=vector_size,
                distance=Distance.COSINE  # Cosine similarity for semantic search
            )
        )
        print(f"Collection '{collection_name}' created successfully!")
    
    def upsert_points(self, collection_name: str, points: List[PointStruct]) -> bool:
        """
        Upsert points to collection
        
        Args:
            collection_name: Name of collection
            points: List of PointStruct objects
            
        Returns:
            True if successful
        """
        try:
            client = self._get_client()
            client.upsert(
                collection_name=collection_name,
                points=points
            )
            return True
        except Exception as e:
            print(f"Error upserting points: {e}")
            return False
    
    def search_similar(
        self, 
        collection_name: str, 
        query_vector: List[float], 
        limit: int = 10,
        score_threshold: float = 0.7,
        filters: Optional[Filter] = None
    ) -> List[Dict[str, Any]]:
        """
        Search for similar vectors
        
        Args:
            collection_name: Name of collection
            query_vector: Query embedding vector
            limit: Number of results
            score_threshold: Minimum similarity score (0-1)
            filters: Optional filters
            
        Returns:
            List of search results
        """
        try:
            client = self._get_client()
            
            # Try new API first (qdrant-client >= 1.7.0)
            try:
                results = client.query_points(
                    collection_name=collection_name,
                    query=query_vector,
                    limit=limit,
                    score_threshold=score_threshold,
                    query_filter=filters
                ).points
            except AttributeError:
                # Fallback to old API (qdrant-client < 1.7.0)
                results = client.search(
                    collection_name=collection_name,
                    query_vector=query_vector,
                    limit=limit,
                    score_threshold=score_threshold,
                    query_filter=filters
                )
            
            return [
                {
                    "id": result.id,
                    "score": result.score,
                    "payload": result.payload
                }
                for result in results
            ]
        except Exception as e:
            print(f"Error searching: {e}")
            import traceback
            traceback.print_exc()
            return []
    
    def delete_by_filter(self, collection_name: str, filters: Filter) -> bool:
        """Delete points by filter"""
        try:
            client = self._get_client()
            client.delete(
                collection_name=collection_name,
                points_selector=filters
            )
            return True
        except Exception as e:
            print(f"Error deleting: {e}")
            return False
    
    def get_collection_info(self, collection_name: str) -> dict | None:
        try:
            client = self._get_client()
            
            info = client.get_collection(collection_name)
            
            points_count = 0
            if hasattr(info, 'points_count'):
                points_count = info.points_count
            elif hasattr(info, 'vectors_count'):
                points_count = info.vectors_count
            elif hasattr(info, 'status') and hasattr(info.status, 'points_count'):
                points_count = info.status.points_count
            
            vectors_config = info.config.params.vectors
            
            if isinstance(vectors_config, dict):
                vector_params = vectors_config.get('') or list(vectors_config.values())[0]
            else:
                vector_params = vectors_config
            
            return {
                "name": collection_name,
                "vector_size": vector_params.size,
                "distance": vector_params.distance.value,
                "points_count": points_count,
            }
        except Exception as e:
            print(f"Error getting collection info: {e}") 
            import traceback
            traceback.print_exc()
            return None
    
    def get_client(self) -> QdrantClient:
        """Public method to get client"""
        return self._get_client()


# Global instance
qdrant_service = QdrantService()