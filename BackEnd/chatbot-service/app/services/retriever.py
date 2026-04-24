from typing import List, Dict, Any, Optional, Literal
from qdrant_client.models import Filter, FieldCondition, MatchValue, MatchAny
from app.services.embedding import embedding_service
from app.services.qdrant import qdrant_service
from app.config import get_settings

settings = get_settings()


def get_adaptive_threshold(intent: str, has_specific_filter: bool) -> float:
    """
    Calculate adaptive similarity threshold.

    Lower when a specific ID filter is already applied (already narrowed scope),
    higher for broad searches where semantic quality matters more.
    """
    if has_specific_filter:
        return 0.30

    thresholds = {
        "cv_analysis": 0.40,
        "jd_search": 0.50,
        "jd_analysis": 0.40,
        "general": 0.35,
        "hr_candidate": 0.30,  # position_id filter is highly specific
    }
    return thresholds.get(intent, 0.45)


def get_adaptive_top_k(intent: str, query_length: int) -> tuple[int, int]:
    """
    Calculate adaptive top_k based on intent and query word count.

    Returns:
        (cv_top_k, jd_top_k)
    """
    base_config = {
        "cv_analysis": (8, 0),
        "jd_search": (5, 5),
        "jd_analysis": (5, 2),
        "general": (3, 3),
    }
    cv_k, jd_k = base_config.get(intent, (5, 3))

    # Boost for complex queries (>15 words)
    if query_length > 15:
        cv_k = min(cv_k + 2, 10)
        jd_k = min(jd_k + 1, 7)

    return cv_k, jd_k


class CareerCounselorRetriever:
    """
    Retrieval layer for both Candidate Chatbot and HR Chatbot.

    TIER 1 IMPROVEMENTS:
    - Adaptive top_k based on intent and query length.
    - Adaptive threshold based on filter specificity.
    - Retrieval quality monitoring.
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
        top_k: Optional[int] = None,
        score_threshold: Optional[float] = None,
        active_jd_ids: Optional[List[int]] = None
    ) -> Dict[str, Any]:
        """Candidate Chatbot: intent-based retrieval with adaptive parameters."""
        query_vector = self.embedding_service.embed_text(query, is_query=True)
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
                score_threshold=score_threshold,
                active_jd_ids=active_jd_ids,
            )

        elif intent == "jd_analysis":
            if jd_id:
                return await self._retrieve_cv_jd_match(
                    query_vector=query_vector,
                    cv_id=cv_id,
                    jd_id=jd_id,
                    cv_top_k=cv_top_k,
                    jd_top_k=jd_top_k,
                    score_threshold=score_threshold,
                )
            else:
                return await self._retrieve_jd_search_with_cv(
                    query_vector=query_vector,
                    candidate_id=candidate_id,
                    cv_id=cv_id,
                    cv_top_k=cv_top_k,
                    jd_top_k=min(jd_top_k, 3),
                    score_threshold=score_threshold,
                    active_jd_ids=active_jd_ids,
                )

        elif intent == "cv_analysis":
            return await self._retrieve_cv_analysis(
                query_vector=query_vector,
                candidate_id=candidate_id,
                cv_id=cv_id,
                cv_top_k=cv_top_k,
                score_threshold=score_threshold,
            )

        else:  # general
            return {"cv_context": [], "jd_context": [], "retrieval_stats": {}}

    async def _retrieve_jd_search_with_cv(
        self,
        query_vector: List[float],
        candidate_id: Optional[str] = None,
        cv_id: Optional[int] = None,
        cv_top_k: int = 5,
        jd_top_k: int = 5,
        score_threshold: Optional[float] = None,
        active_jd_ids: Optional[List[int]] = None,
    ) -> Dict[str, Any]:
        """
        Retrieve CV context (Candidate Chatbot) and JD context sequentially.

        Note: synchronous QdrantClient is not thread-safe for concurrent calls
        via run_in_executor — executed sequentially on purpose.
        """
        has_cv_filter = cv_id is not None or candidate_id is not None
        if score_threshold is None:
            cv_threshold = get_adaptive_threshold("jd_search", has_cv_filter)
            jd_threshold = get_adaptive_threshold("jd_search", False)
        else:
            cv_threshold = jd_threshold = score_threshold

        print(f"[Retrieval] CV threshold: {cv_threshold:.2f}, JD threshold: {jd_threshold:.2f}")

        # --- CV filter ---
        cv_must = [FieldCondition(key="is_latest", match=MatchValue(value=True))]
        if cv_id:
            cv_must.append(FieldCondition(key="cvId", match=MatchValue(value=cv_id)))
        elif candidate_id:
            cv_must.append(FieldCondition(key="candidateId", match=MatchValue(value=candidate_id)))
        cv_filters = Filter(must=cv_must)

        cv_results = self.qdrant_service.search_similar(
            collection_name=self.cv_collection,
            query_vector=query_vector,
            limit=cv_top_k,
            score_threshold=cv_threshold,
            filters=cv_filters,
        )

        # --- JD filter ---
        jd_must = []
        if active_jd_ids is not None:
            jd_must.append(FieldCondition(key="positionId", match=MatchAny(any=active_jd_ids)))
        jd_filters = Filter(must=jd_must) if jd_must else None

        jd_results = self.qdrant_service.search_similar(
            collection_name=self.jd_collection,
            query_vector=query_vector,
            limit=jd_top_k,
            score_threshold=jd_threshold,
            filters=jd_filters,
        )

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
                    "jd_threshold": jd_threshold,
                },
            },
        }

    async def _retrieve_cv_jd_match(
        self,
        query_vector: List[float],
        cv_id: Optional[int],
        jd_id: Optional[int],
        cv_top_k: int = 5,
        jd_top_k: int = 2,
        score_threshold: Optional[float] = None,
    ) -> Dict[str, Any]:
        """Retrieve specific CV chunks and JD chunks for a CV-JD analysis turn."""
        if not cv_id or not jd_id:
            print(f"[Warning] cv_jd_match called without cv_id or jd_id")
            return {
                "cv_context": [],
                "jd_context": [],
                "retrieval_stats": {"error": "Missing cv_id or jd_id"},
            }

        threshold = score_threshold if score_threshold is not None else get_adaptive_threshold("jd_analysis", True)
        print(f"[Retrieval] Using threshold: {threshold:.2f} (specific CV+JD match)")

        cv_filters = Filter(
            must=[
                FieldCondition(key="cvId", match=MatchValue(value=cv_id)),
                FieldCondition(key="is_latest", match=MatchValue(value=True)),
            ]
        )
        cv_results = self.qdrant_service.search_similar(
            collection_name=self.cv_collection,
            query_vector=query_vector,
            limit=cv_top_k,
            score_threshold=threshold,
            filters=cv_filters,
        )

        # JD chunks are replaced atomically by positionId — no is_latest field
        jd_filters = Filter(
            must=[FieldCondition(key="positionId", match=MatchValue(value=jd_id))]
        )
        jd_results = self.qdrant_service.search_similar(
            collection_name=self.jd_collection,
            query_vector=query_vector,
            limit=jd_top_k,
            score_threshold=0.0,  # Always retrieve when filter is already specific
            filters=jd_filters,
        )

        cv_quality = self._assess_retrieval_quality(cv_results, "jd_analysis")
        print(f"[Quality] CV: {cv_quality['quality']} (max={cv_quality['max_score']:.2f})")
        print(f"[Quality] JD: Retrieved {len(jd_results)} specific JD chunks")

        return {
            "cv_context": cv_results,
            "jd_context": jd_results,
            "retrieval_stats": {
                "cv_chunks_retrieved": len(cv_results),
                "jd_retrieved": len(jd_results) > 0,
                "cv_score_range": self._get_score_range(cv_results),
                "jd_score_range": self._get_score_range(jd_results),
                "cv_quality": cv_quality,
                "threshold_used": threshold,
            },
        }

    async def _retrieve_cv_analysis(
        self,
        query_vector: List[float],
        candidate_id: Optional[str] = None,
        cv_id: Optional[int] = None,
        cv_top_k: int = 8,
        score_threshold: Optional[float] = None,
    ) -> Dict[str, Any]:
        """Retrieve CV chunks for a pure CV analysis turn (no JD needed)."""
        has_filter = cv_id is not None or candidate_id is not None
        threshold = score_threshold if score_threshold is not None else get_adaptive_threshold("cv_analysis", has_filter)

        print(f"[Retrieval] CV analysis threshold: {threshold:.2f}")

        must_conditions = [FieldCondition(key="is_latest", match=MatchValue(value=True))]
        if cv_id:
            must_conditions.append(FieldCondition(key="cvId", match=MatchValue(value=cv_id)))
        elif candidate_id:
            must_conditions.append(FieldCondition(key="candidateId", match=MatchValue(value=candidate_id)))

        cv_filters = Filter(must=must_conditions)
        cv_results = self.qdrant_service.search_similar(
            collection_name=self.cv_collection,
            query_vector=query_vector,
            limit=cv_top_k,
            score_threshold=threshold,
            filters=cv_filters,
        )

        cv_quality = self._assess_retrieval_quality(cv_results, "cv_analysis")
        print(f"[Quality] CV: {cv_quality['quality']} (max={cv_quality['max_score']:.2f})")

        return {
            "cv_context": cv_results,
            "jd_context": [],
            "retrieval_stats": {
                "cv_chunks_retrieved": len(cv_results),
                "jd_docs_used": 0,
                "cv_score_range": self._get_score_range(cv_results),
                "cv_quality": cv_quality,
                "threshold_used": threshold,
            },
        }

    # ---------------------------------------------------------------------------
    # HR Chatbot: HR Mode
    # ---------------------------------------------------------------------------

    async def retrieve_for_hr_mode_hr(
        self,
        query: str,
        position_id: int,
        top_k: int = 10,
        score_threshold: float = 0.35,
    ) -> Dict[str, Any]:
        """
        HR Chatbot — HR Mode.
        Retrieve CVs uploaded by HR for a specific position.
        Filter: positionId + sourceType=HR + is_latest=True
        """
        query_vector = self.embedding_service.embed_text(query, is_query=True)

        cv_filters = Filter(
            must=[
                FieldCondition(key="positionId", match=MatchValue(value=position_id)),
                FieldCondition(key="sourceType", match=MatchValue(value="HR")),
                FieldCondition(key="is_latest", match=MatchValue(value=True)),
            ]
        )

        cv_results = self.qdrant_service.search_similar(
            collection_name=self.cv_collection,
            query_vector=query_vector,
            limit=top_k,
            score_threshold=score_threshold,
            filters=cv_filters,
        )

        # Phase 4: Also retrieve JD context for HR mode to provide position name/reqs
        jd_filters = Filter(
            must=[FieldCondition(key="positionId", match=MatchValue(value=position_id))]
        )
        jd_results = self.qdrant_service.search_similar(
            collection_name=self.jd_collection,
            query_vector=query_vector,
            limit=3,
            score_threshold=0.0,
            filters=jd_filters,
        )

        return {
            "cv_context": cv_results,
            "jd_context": jd_results,
            "retrieval_stats": {
                "cv_chunks_retrieved": len(cv_results),
                "jd_chunks_retrieved": len(jd_results),
                "cv_score_range": self._get_score_range(cv_results),
                "threshold_used": score_threshold,
                "position_id": position_id,
                "source_type": "HR",
            },
        }

    # ---------------------------------------------------------------------------
    # HR Chatbot: Candidate Mode  (Phase 3 — applied_position_ids architecture)
    # ---------------------------------------------------------------------------

    async def retrieve_for_hr_mode_candidate(
        self,
        query: str,
        position_id: int,
        top_k: int = 10,
        score_threshold: Optional[float] = None,
        active_jd_ids: Optional[List[int]] = None,
    ) -> Dict[str, Any]:
        """
        HR Chatbot — Candidate Mode (Phase 3).

        Instead of passing potentially hundreds of candidate_ids via MatchAny
        (slow, bloated payload), we query directly using:
            applied_position_ids contains position_id
            sourceType = CANDIDATE
            is_latest = True

        This requires Master CV payloads in Qdrant to carry the
        `applied_position_ids` array (populated by cv_consumer.py and kept
        up-to-date by the sync script after each new application).

        JD context is also retrieved so the LLM can cross-reference job
        requirements against candidate profiles.
        """
        query_vector = self.embedding_service.embed_text(query, is_query=True)
        threshold = score_threshold if score_threshold is not None else get_adaptive_threshold("hr_candidate", True)

        print(f"[Retrieval] CANDIDATE_MODE — position_id={position_id}, threshold={threshold:.2f}")

        # --- CV filter: query by applied_position_ids membership ---
        cv_filters = Filter(
            must=[
                FieldCondition(
                    key="applied_position_ids",
                    match=MatchAny(any=[position_id]),
                ),
                FieldCondition(key="sourceType", match=MatchValue(value="CANDIDATE")),
                FieldCondition(key="is_latest", match=MatchValue(value=True)),
            ]
        )

        cv_results = self.qdrant_service.search_similar(
            collection_name=self.cv_collection,
            query_vector=query_vector,
            limit=top_k,
            score_threshold=threshold,
            filters=cv_filters,
        )

        print(f"[Retrieval] CANDIDATE_MODE CV chunks found: {len(cv_results)}")

        # --- JD filter: retrieve relevant JD chunks for this position ---
        jd_results = []
        jd_ids_to_search = active_jd_ids if active_jd_ids else [position_id]
        jd_filters = Filter(
            must=[
                FieldCondition(
                    key="positionId",
                    match=MatchAny(any=jd_ids_to_search),
                )
            ]
        )
        jd_results = self.qdrant_service.search_similar(
            collection_name=self.jd_collection,
            query_vector=query_vector,
            limit=3,
            score_threshold=0.0,  # Always retrieve JD when filter is already specific
            filters=jd_filters,
        )

        print(f"[Retrieval] CANDIDATE_MODE JD chunks found: {len(jd_results)}")

        return {
            "cv_context": cv_results,
            "jd_context": jd_results,
            "retrieval_stats": {
                "cv_chunks_retrieved": len(cv_results),
                "jd_chunks_retrieved": len(jd_results),
                "cv_score_range": self._get_score_range(cv_results),
                "jd_score_range": self._get_score_range(jd_results),
                "threshold_used": threshold,
                "position_id": position_id,
                "source_type": "CANDIDATE",
                "filter_strategy": "applied_position_ids",
            },
        }

    # ---------------------------------------------------------------------------
    # Helpers
    # ---------------------------------------------------------------------------

    async def retrieve_cv_by_skills(
        self,
        required_skills: List[str],
        top_k: int = 5,
        seniority_level: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Retrieve CVs by skill keyword matching via Qdrant metadata filter."""
        must_conditions = [
            FieldCondition(key="is_latest", match=MatchValue(value=True)),
            FieldCondition(key="skills", match=MatchAny(any=required_skills)),
        ]
        if seniority_level:
            must_conditions.append(
                FieldCondition(key="seniorityLevel", match=MatchValue(value=seniority_level))
            )

        filters = Filter(must=must_conditions)
        generic_query = " ".join(required_skills)
        query_vector = self.embedding_service.embed_text(generic_query, is_query=True)
        threshold = get_adaptive_threshold("cv_analysis", True)

        results = self.qdrant_service.search_similar(
            collection_name=self.cv_collection,
            query_vector=query_vector,
            limit=top_k,
            score_threshold=threshold,
            filters=filters,
        )

        return {
            "cv_context": results,
            "jd_context": [],
            "retrieval_stats": {
                "cv_chunks_retrieved": len(results),
                "skills_queried": required_skills,
                "score_range": self._get_score_range(results),
            },
        }

    def _get_score_range(self, results: List[Dict]) -> Dict[str, float]:
        """Extract min/max similarity scores from a result list."""
        if not results:
            return {"min": 0.0, "max": 0.0}
        scores = [r["score"] for r in results]
        return {"min": min(scores), "max": max(scores)}

    def _assess_retrieval_quality(self, results: List[Dict], intent: str) -> Dict[str, Any]:
        """
        Classify retrieval quality as GOOD / ACCEPTABLE / POOR.

        Thresholds are intent-specific to avoid false positives on broad searches.
        """
        if not results:
            return {
                "quality": "POOR",
                "max_score": 0.0,
                "avg_score": 0.0,
                "count": 0,
                "recommendation": "No results — consider relaxing filters or rewriting query",
            }

        scores = [r["score"] for r in results]
        max_score = max(scores)
        avg_score = sum(scores) / len(scores)

        thresholds = {
            "cv_analysis": {"good": 0.60, "acceptable": 0.40},
            "jd_search": {"good": 0.70, "acceptable": 0.50},
            "jd_analysis": {"good": 0.60, "acceptable": 0.40},
        }
        t = thresholds.get(intent, {"good": 0.60, "acceptable": 0.40})

        if max_score >= t["good"]:
            quality = "GOOD"
            recommendation = "High quality retrieval"
        elif max_score >= t["acceptable"]:
            quality = "ACCEPTABLE"
            recommendation = "Acceptable quality — results may be less precise"
        else:
            quality = "POOR"
            recommendation = "Low quality — consider query rewriting or expanding search"

        return {
            "quality": quality,
            "max_score": round(max_score, 3),
            "avg_score": round(avg_score, 3),
            "count": len(results),
            "recommendation": recommendation,
        }


# Global singleton instance
retriever = CareerCounselorRetriever()