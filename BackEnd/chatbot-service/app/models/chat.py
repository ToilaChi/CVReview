from pydantic import BaseModel, Field
from typing import Optional, Dict, Any, List
from datetime import datetime


class ChatRequest(BaseModel):
    query: str = Field(..., min_length=1, max_length=1000)
    candidate_id: Optional[str] = None
    cv_id: Optional[int] = None
    jd_id: Optional[int] = None
    conversation_history: Optional[List[Dict[str, str]]] = Field(
        None,
        description="Previous conversation turns for context-aware responses",
        examples=[[{"query": "Find jobs for me", "answer": "Here are 3 suitable positions..."}]]
    )
    stream: bool = False


class RetrievalStats(BaseModel):
    """Statistics about retrieval"""
    cv_chunks_retrieved: int = 0
    jd_docs_retrieved: int = 0
    score_range: Optional[Dict[str, float]] = None


class ChatMetadata(BaseModel):
    """Metadata about the chat response"""
    intent: str
    intent_confidence: float
    cv_chunks_used: int
    jd_docs_used: int
    retrieval_stats: Dict[str, Any]
    processing_time_ms: Optional[float] = None


class ChatResponse(BaseModel):
    """Response schema for chat endpoint"""
    
    answer: str = Field(
        ...,
        description="AI-generated answer"
    )
    
    metadata: ChatMetadata = Field(
        ...,
        description="Metadata about the response"
    )
    
    timestamp: datetime = Field(
        default_factory=datetime.now,
        description="Response timestamp"
    )
    
    class Config:
        json_schema_extra = {
            "example": {
                "answer": "Based on your CV, you have strong Python skills including FastAPI, Django, and 5 years of experience...",
                "metadata": {
                    "intent": "cv_analysis",
                    "intent_confidence": 0.85,
                    "cv_chunks_used": 3,
                    "jd_docs_used": 0,
                    "retrieval_stats": {
                        "cv_chunks_retrieved": 3,
                        "score_range": {"min": 0.69, "max": 0.82}
                    },
                    "processing_time_ms": 1250.5
                },
                "timestamp": "2024-12-15T10:30:00"
            }
        }


class ErrorResponse(BaseModel):
    """Error response schema"""
    
    error: str = Field(..., description="Error message")
    detail: Optional[str] = Field(None, description="Detailed error information")
    timestamp: datetime = Field(default_factory=datetime.now)
    
    class Config:
        json_schema_extra = {
            "example": {
                "error": "Invalid request",
                "detail": "Query cannot be empty",
                "timestamp": "2024-12-15T10:30:00"
            }
        }


class HealthResponse(BaseModel):
    """Health check response"""
    
    status: str = "ok"
    service: str = "chatbot-service"
    timestamp: datetime = Field(default_factory=datetime.now)
    components: Dict[str, str] = Field(
        default_factory=lambda: {
            "embedding_service": "unknown",
            "qdrant": "unknown",
            "llm": "unknown"
        }
    )
    
    class Config:
        json_schema_extra = {
            "example": {
                "status": "ok",
                "service": "chatbot-service",
                "timestamp": "2024-12-15T10:30:00",
                "components": {
                    "embedding_service": "ok",
                    "qdrant": "ok",
                    "llm": "ok"
                }
            }
        }