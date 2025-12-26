from typing import List, Dict, Any, Optional, Literal
from qdrant_client.models import Filter, FieldCondition, MatchValue, MatchAny
from app.services.embedding import embedding_service
from app.services.qdrant import qdrant_service
from app.config import get_settings

settings = get_settings()


def get_adaptive_threshold(intent: str, has_specific_filter: bool) -> float:
    """
    Calculate adaptive threshold based on intent and filter specificity
    
    Logic:
    - Lower threshold when we have specific filters (cv_id, jd_id)
      → Already filtered, can be more lenient on similarity
    - Higher threshold for broad searches
      → Need stronger semantic match
    
    Args:
        intent: User intent (jd_search, jd_analysis, cv_analysis)
        has_specific_filter: Whether we're filtering by specific IDs
        
    Returns:
        Score threshold (0.0 - 1.0)
    """
    if has_specific_filter:
        # Has cv_id or jd_id → search within specific scope
        return 0.30  # Lower threshold
    
    # Broad search → need higher quality
    thresholds = {
        "cv_analysis": 0.40,  # Medium - searching within 1 CV
        "jd_search": 0.50,    # Higher - searching all JDs
        "jd_analysis": 0.40,  # Medium - usually has context
        "general": 0.35       # Lower for general queries
    }
    
    return thresholds.get(intent, 0.45)


# Adaptive top_k helper
def get_adaptive_top_k(intent: str, query_length: int) -> tuple[int, int]:
    """
    Calculate adaptive top_k based on intent and query complexity
    
    Logic:
    - Longer queries → might need more chunks to cover all aspects
    - Different intents have different context needs
    
    Args:
        intent: User intent
        query_length: Number of words in query
        
    Returns:
        (cv_top_k, jd_top_k)
    """
    # Base values
    base_config = {
        "cv_analysis": (8, 0),   # Increased from (5, 0)
        "jd_search": (5, 5),     # Increased from (3, 3)
        "jd_analysis": (5, 2),   # Increased from (2, 2)
        "general": (3, 3)
    }
    
    cv_k, jd_k = base_config.get(intent, (5, 3))
    
    # Boost for complex queries (>15 words)
    if query_length > 15:
        cv_k = min(cv_k + 2, 10)  # Cap at 10
        jd_k = min(jd_k + 1, 7)   # Cap at 7
    
    return cv_k, jd_k


class CareerCounselorRetriever:
    """
    TIER 1 IMPROVEMENTS:
    - Adaptive top_k based on intent and query length
    - Adaptive threshold based on filter specificity
    - Better retrieval quality monitoring
    """
    
    def __init__(self):
        self.embedding_service = embedding_service
        self.qdrant_service = qdrant_service
        self.cv_collection = settings.CV_COLLECTION_NAME
        self.jd_collection = settings.JD_COLLECTION_NAME
    
    async def retrieve_for_intent(
        self,
        query: str,
        intent: Literal["jd_search", "jd_analysis", "cv_analysis", "general"],
        candidate_id: Optional[str] = None,
        cv_id: Optional[int] = None,
        jd_id: Optional[int] = None,
        top_k: Optional[int] = None,  # Made optional, will use adaptive
        score_threshold: Optional[float] = None  # Made optional, will use adaptive
    ) -> Dict[str, Any]:
        """
        Improved retrieval with adaptive parameters
        """
        query_vector = self.embedding_service.embed_text(query, is_query=True)
        
        # Calculate adaptive parameters
        query_length = len(query.split())
        
        if top_k is None:
            cv_top_k, jd_top_k = get_adaptive_top_k(intent, query_length)
        else:
            cv_top_k = jd_top_k = top_k
        
        print(f"[Retriever] Intent: {intent}, Query length: {query_length} words")
        print(f"[Retriever] Adaptive top_k: CV={cv_top_k}, JD={jd_top_k}")
        
        if intent == "jd_search":
            return await self._retrieve_jd_search_with_cv(
                query_vector=query_vector,
                candidate_id=candidate_id,
                cv_id=cv_id,
                cv_top_k=cv_top_k,
                jd_top_k=jd_top_k,
                score_threshold=score_threshold
            )
        
        elif intent == "jd_analysis":
            if jd_id:
                return await self._retrieve_cv_jd_match(
                    query_vector=query_vector,
                    cv_id=cv_id,
                    jd_id=jd_id,
                    cv_top_k=cv_top_k,
                    jd_top_k=jd_top_k,
                    score_threshold=score_threshold
                )
            else:
                return await self._retrieve_jd_search_with_cv(
                    query_vector=query_vector,
                    candidate_id=candidate_id,
                    cv_id=cv_id,
                    cv_top_k=cv_top_k,
                    jd_top_k=min(jd_top_k, 3),  # Fewer JDs for analysis
                    score_threshold=score_threshold
                )
        
        elif intent == "cv_analysis":
            return await self._retrieve_cv_analysis(
                query_vector=query_vector,
                candidate_id=candidate_id,
                cv_id=cv_id,
                cv_top_k=cv_top_k,
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
        cv_top_k: int = 5,
        jd_top_k: int = 5,
        score_threshold: Optional[float] = None
    ) -> Dict[str, Any]:
        """
        Improved: Adaptive threshold and better monitoring
        """
        # Adaptive threshold
        has_cv_filter = cv_id is not None or candidate_id is not None
        if score_threshold is None:
            cv_threshold = get_adaptive_threshold("jd_search", has_cv_filter)
            jd_threshold = get_adaptive_threshold("jd_search", False)  # JD search is always broad
        else:
            cv_threshold = jd_threshold = score_threshold
        
        print(f"[Retrieval] CV threshold: {cv_threshold:.2f}, JD threshold: {jd_threshold:.2f}")
        
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
            limit=cv_top_k,
            score_threshold=cv_threshold,
            filters=cv_filters
        )
        
        # 2. Search JDs
        jd_filters = Filter(
            must=[
                FieldCondition(key="is_latest", match=MatchValue(value=True))
            ]
        )
        
        jd_results = self.qdrant_service.search_similar(
            collection_name=self.jd_collection,
            query_vector=query_vector,
            limit=jd_top_k,
            score_threshold=jd_threshold,
            filters=jd_filters
        )
        
        # Quality assessment
        cv_quality = self._assess_retrieval_quality(cv_results, "jd_search")
        jd_quality = self._assess_retrieval_quality(jd_results, "jd_search")
        
        print(f"[Quality] CV: {cv_quality['quality']} (max={cv_quality['max_score']:.2f})")
        print(f"[Quality] JD: {jd_quality['quality']} (max={jd_quality['max_score']:.2f})")
        
        return {
            "cv_context": cv_results,
            "jd_context": jd_results,
            "retrieval_stats": {
                "cv_chunks_retrieved": len(cv_results),
                "jd_positions_retrieved": len(jd_results),
                "cv_score_range": self._get_score_range(cv_results),
                "jd_score_range": self._get_score_range(jd_results),
                "cv_quality": cv_quality,  
                "jd_quality": jd_quality,  
                "thresholds_used": {  
                    "cv_threshold": cv_threshold,
                    "jd_threshold": jd_threshold
                }
            }
        }

    async def _retrieve_cv_jd_match(
        self,
        query_vector: List[float],
        cv_id: Optional[int],  
        jd_id: Optional[int],
        cv_top_k: int = 5,
        jd_top_k: int = 2,
        score_threshold: Optional[float] = None
    ) -> Dict[str, Any]:
        """
        Improved: Adaptive threshold
        """
        if not cv_id or not jd_id:
            print(f"[Warning] cv_jd_match called without cv_id or jd_id")
            return {
                "cv_context": [],
                "jd_context": [],
                "retrieval_stats": {"error": "Missing cv_id or jd_id"}
            }

        # Adaptive threshold (both have specific IDs)
        if score_threshold is None:
            threshold = get_adaptive_threshold("jd_analysis", True)
        else:
            threshold = score_threshold
        
        print(f"[Retrieval] Using threshold: {threshold:.2f} (specific CV+JD match)")

        # Get CV chunks
        cv_filters = Filter(
            must=[
                FieldCondition(key="cvId", match=MatchValue(value=cv_id)),
                FieldCondition(key="is_latest", match=MatchValue(value=True))
            ]
        )
        
        cv_results = self.qdrant_service.search_similar(
            collection_name=self.cv_collection,
            query_vector=query_vector,
            limit=cv_top_k,
            score_threshold=threshold,
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
            limit=1,  # Just get the specific JD
            score_threshold=0.0,  # Always get the JD
            filters=jd_filters
        )
        
        # Quality assessment
        cv_quality = self._assess_retrieval_quality(cv_results, "jd_analysis")
        
        print(f"[Quality] CV: {cv_quality['quality']} (max={cv_quality['max_score']:.2f})")
        print(f"[Quality] JD: Retrieved {len(jd_results)} specific JD")
        
        return {
            "cv_context": cv_results,
            "jd_context": jd_results,
            "retrieval_stats": {
                "cv_chunks_retrieved": len(cv_results),
                "jd_retrieved": len(jd_results) > 0,
                "cv_score_range": self._get_score_range(cv_results),
                "jd_score_range": self._get_score_range(jd_results),
                "cv_quality": cv_quality,  
                "threshold_used": threshold  
            }
        }
    
    async def _retrieve_cv_analysis(
        self,
        query_vector: List[float],
        candidate_id: Optional[str] = None,
        cv_id: Optional[int] = None,
        cv_top_k: int = 8,
        score_threshold: Optional[float] = None
    ) -> Dict[str, Any]:
        """
        NEW: Dedicated CV analysis retrieval
        """
        has_filter = cv_id is not None or candidate_id is not None
        
        if score_threshold is None:
            threshold = get_adaptive_threshold("cv_analysis", has_filter)
        else:
            threshold = score_threshold
        
        print(f"[Retrieval] CV analysis threshold: {threshold:.2f}")
        
        # Build filter
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
            limit=cv_top_k,
            score_threshold=threshold,
            filters=cv_filters
        )
        
        # Quality assessment
        cv_quality = self._assess_retrieval_quality(cv_results, "cv_analysis")
        
        print(f"[Quality] CV: {cv_quality['quality']} (max={cv_quality['max_score']:.2f})")
        
        return {
            "cv_context": cv_results,
            "jd_context": [],  # CV analysis doesn't need JDs
            "retrieval_stats": {
                "cv_chunks_retrieved": len(cv_results),
                "jd_docs_used": 0,
                "cv_score_range": self._get_score_range(cv_results),
                "cv_quality": cv_quality,  
                "threshold_used": threshold  
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
    
    # Retrieval quality assessment
    def _assess_retrieval_quality(self, results: List[Dict], intent: str) -> Dict[str, Any]:
        """
        Assess quality of retrieval results
        
        Returns:
            {
                "quality": "GOOD" | "ACCEPTABLE" | "POOR",
                "max_score": float,
                "avg_score": float,
                "count": int,
                "recommendation": str
            }
        """
        if not results:
            return {
                "quality": "POOR",
                "max_score": 0.0,
                "avg_score": 0.0,
                "count": 0,
                "recommendation": "No results found - consider relaxing filters or query expansion"
            }
        
        scores = [r['score'] for r in results]
        max_score = max(scores)
        avg_score = sum(scores) / len(scores)
        
        # Quality thresholds by intent
        thresholds = {
            "cv_analysis": {"good": 0.60, "acceptable": 0.40},
            "jd_search": {"good": 0.70, "acceptable": 0.50},
            "jd_analysis": {"good": 0.60, "acceptable": 0.40}
        }
        
        threshold = thresholds.get(intent, {"good": 0.60, "acceptable": 0.40})
        
        if max_score >= threshold["good"]:
            quality = "GOOD"
            recommendation = "High quality retrieval"
        elif max_score >= threshold["acceptable"]:
            quality = "ACCEPTABLE"
            recommendation = "Acceptable quality - results may be less precise"
        else:
            quality = "POOR"
            recommendation = "Low quality - consider query rewriting or expanding search"
        
        return {
            "quality": quality,
            "max_score": round(max_score, 3),
            "avg_score": round(avg_score, 3),
            "count": len(results),
            "recommendation": recommendation
        }
    
    async def retrieve_cv_by_skills(
        self,
        required_skills: List[str],
        top_k: int = 5,
        seniority_level: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Retrieve CVs by skill matching (metadata filter)
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
        
        generic_query = " ".join(required_skills)
        query_vector = self.embedding_service.embed_text(generic_query, is_query=True)
        
        # Use adaptive threshold
        threshold = get_adaptive_threshold("cv_analysis", True)
        
        results = self.qdrant_service.search_similar(
            collection_name=self.cv_collection,
            query_vector=query_vector,
            limit=top_k,
            score_threshold=threshold,
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