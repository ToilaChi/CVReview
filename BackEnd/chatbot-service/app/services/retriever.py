from typing import List, Dict, Any, Optional, Literal
from qdrant_client.models import Filter, FieldCondition, MatchValue, MatchAny
from app.services.embedding import embedding_service
from app.services.qdrant import qdrant_service
from app.config import get_settings

settings = get_settings()


class RetrievalResult:
    """Single retrieval result"""
    def __init__(self, id: int, score: float, payload: dict):
        self.id = id
        self.score = score
        self.payload = payload
        
    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "score": self.score,
            "payload": self.payload
        }


class CareerCounselorRetriever:
    """
    Retriever for career counseling chatbot
    Handles CV and JD retrieval with BGE embeddings
    """
    
    def __init__(self):
        self.embedding_service = embedding_service
        self.qdrant_service = qdrant_service
        self.cv_collection = settings.CV_COLLECTION_NAME
        self.jd_collection = settings.JD_COLLECTION_NAME
    
    async def retrieve_for_intent(
    self,
    query: str,
    intent: Literal["jd_search", "jd_analysis", "general"],  # ← Đổi intents
    candidate_id: Optional[str] = None,
    cv_id: Optional[int] = None,
    jd_id: Optional[int] = None,
    top_k: int = 5,
    score_threshold: float = 0.5
    ) -> Dict[str, Any]:
        
        query_vector = self.embedding_service.embed_text(query, is_query=True)
        
        # Log để debug
        print(f"[Retriever] Intent: {intent}, candidate_id: {candidate_id}, cv_id: {cv_id}, jd_id: {jd_id}")
        
        if intent == "jd_search":
            # Use case 1: Tìm job phù hợp với CV
            return await self._retrieve_jd_search_with_cv(
                query_vector=query_vector,
                candidate_id=candidate_id,
                cv_id=cv_id,
                top_k=top_k,
                score_threshold=score_threshold
            )
        
        elif intent == "jd_analysis":
            # Use case 2: Phân tích JD cụ thể hoặc match CV-JD
            if jd_id:
                # Có JD cụ thể → Match CV-JD
                return await self._retrieve_cv_jd_match(
                    query_vector=query_vector,
                    cv_id=cv_id,
                    jd_id=jd_id,
                    top_k=top_k,
                    score_threshold=score_threshold
                )
            else:
                # Không có JD cụ thể → Tìm JD rồi phân tích
                return await self._retrieve_jd_search_with_cv(
                    query_vector=query_vector,
                    candidate_id=candidate_id,
                    cv_id=cv_id,
                    top_k=3,  # Ít JD hơn khi phân tích
                    score_threshold=score_threshold
                )
        
        else:  # general
            return {
                "cv_context": [], 
                "jd_context": [], 
                "retrieval_stats": {}
            }
    
    async def _retrieve_jd_search_with_cv(
    self,
    query_vector: List[float],
    candidate_id: Optional[str] = None,
    cv_id: Optional[int] = None,
    top_k: int = 5,
    score_threshold: float = 0.5
    ) -> Dict[str, Any]:
        """
        Tìm JD phù hợp với CV của candidate
        
        Strategy:
        1. Lấy CV chunks của candidate (để hiểu profile)
        2. Search JD phù hợp nhất với CV
        3. Return cả CV và top JDs
        """
        # 1. Get CV context
        cv_filters = None
        must_conditions = [
            FieldCondition(key="is_latest", match=MatchValue(value=True))
        ]
        
        if cv_id:
            must_conditions.append(
                FieldCondition(key="cvId", match=MatchValue(value=cv_id))
            )
        elif candidate_id:
            must_conditions.append(
                FieldCondition(key="candidateId", match=MatchValue(value=candidate_id))
            )
        
        cv_filters = Filter(must=must_conditions)
        
        cv_results = self.qdrant_service.search_similar(
            collection_name=self.cv_collection,
            query_vector=query_vector,
            limit=3,  # Lấy ít thôi, để context gọn
            score_threshold=0.3,
            filters=cv_filters
        )
        
        # 2. Search JDs (semantic search toàn bộ)
        jd_filters = Filter(
            must=[
                FieldCondition(key="is_latest", match=MatchValue(value=True))
            ]
        )
        
        jd_results = self.qdrant_service.search_similar(
            collection_name=self.jd_collection,
            query_vector=query_vector,
            limit=top_k,
            score_threshold=score_threshold,
            filters=jd_filters
        )
        
        return {
            "cv_context": cv_results,
            "jd_context": jd_results,
            "retrieval_stats": {
                "cv_chunks_retrieved": len(cv_results),
                "jd_positions_retrieved": len(jd_results),
                "cv_score_range": self._get_score_range(cv_results),
                "jd_score_range": self._get_score_range(jd_results)
            }
        }

    async def _retrieve_cv_jd_match(
        self,
        query_vector: List[float],
        cv_id: Optional[int],  
        jd_id: Optional[int],
        top_k: int = 3,
        score_threshold: float = 0.5
    ) -> Dict[str, Any]:
        """
        Retrieve specific CV and JD for matching
        
        Strategy:
        - Get top-k relevant CV chunks (semantic)
        - Get specific JD by ID
        - Return both for comparison
        """
        # Validate inputs
        if not cv_id or not jd_id:
            print(f"[Warning] cv_jd_match called without cv_id or jd_id")
            return {
                "cv_context": [],
                "jd_context": [],
                "retrieval_stats": {"error": "Missing cv_id or jd_id"}
            }

        # Get CV chunks (semantic search within this CV)
        cv_filters = Filter(
            must=[
                FieldCondition(key="cvId", match=MatchValue(value=cv_id)),
                FieldCondition(key="is_latest", match=MatchValue(value=True))
            ]
        )
        
        cv_results = self.qdrant_service.search_similar(
            collection_name=self.cv_collection,
            query_vector=query_vector,
            limit=top_k,
            score_threshold=score_threshold,
            filters=cv_filters
        )
        
        # Get specific JD
        jd_filters = Filter(
            must=[
                FieldCondition(key="jdId", match=MatchValue(value=jd_id)),
                FieldCondition(key="is_latest", match=MatchValue(value=True))
            ]
        )
        
        jd_results = self.qdrant_service.search_similar(
            collection_name=self.jd_collection,
            query_vector=query_vector,
            limit=1,
            score_threshold=0.0,  # Always get the JD even if low similarity
            filters=jd_filters
        )
        
        return {
            "cv_context": cv_results,
            "jd_context": jd_results,
            "retrieval_stats": {
                "cv_chunks_retrieved": len(cv_results),
                "jd_retrieved": len(jd_results) > 0,
                "cv_score_range": self._get_score_range(cv_results),
                "jd_score_range": self._get_score_range(jd_results)
            }
        }
    
    def _get_score_range(self, results: List[Dict]) -> Dict[str, float]:
        """Get min/max scores from results"""
        if not results:
            return {"min": 0.0, "max": 0.0}
        
        scores = [r["score"] for r in results]
        return {
            "min": min(scores),
            "max": max(scores)
        }
    
    async def retrieve_cv_by_skills(
        self,
        required_skills: List[str],
        top_k: int = 5,
        seniority_level: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Retrieve CVs by skill matching (metadata filter)
        
        Args:
            required_skills: List of required skills
            top_k: Number of results
            seniority_level: Optional seniority filter
            
        Returns:
            Dict with matching CVs
        """
        must_conditions = [
            FieldCondition(key="is_latest", match=MatchValue(value=True)),
            FieldCondition(key="skills", match=MatchAny(any=required_skills))
        ]
        
        if seniority_level:
            must_conditions.append(
                FieldCondition(key="seniorityLevel", match=MatchValue(value=seniority_level))
            )
        
        filters = Filter(must=must_conditions)
        
        # Use a generic query for broad search
        generic_query = " ".join(required_skills)
        query_vector = self.embedding_service.embed_text(generic_query, is_query=True)
        
        results = self.qdrant_service.search_similar(
            collection_name=self.cv_collection,
            query_vector=query_vector,
            limit=top_k,
            score_threshold=0.3,  # Lower threshold for metadata-filtered search
            filters=filters
        )
        
        return {
            "cv_context": results,
            "jd_context": [],
            "retrieval_stats": {
                "cv_chunks_retrieved": len(results),
                "skills_queried": required_skills,
                "score_range": self._get_score_range(results)
            }
        }


# Global instance
retriever = CareerCounselorRetriever()