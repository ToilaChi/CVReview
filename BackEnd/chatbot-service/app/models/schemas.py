from pydantic import BaseModel, Field
from typing import List, Optional
from datetime import datetime


# ===== CV Models =====
class CVChunk(BaseModel):
    """Single chunk from CV"""
    candidateId: Optional[str] = None
    hrId: Optional[str] = None
    position: Optional[str] = None
    section: str
    chunkIndex: int
    chunkText: str
    words: int
    tokensEstimate: int
    email: str
    cvId: int
    cvStatus: str
    sourceType: str
    createdAt: str
    skills: List[str]
    experienceYears: Optional[int] = None
    seniorityLevel: str
    companies: List[str] = []
    degrees: List[str] = []
    dateRanges: List[str] = []


class CVEmbeddingRequest(BaseModel):
    """Request to embed CV chunks"""
    cvId: int
    candidateName: str
    totalWords: int
    chunks: List[CVChunk]
    totalChunks: int
    position: str
    candidateId: Optional[str] = None
    totalTokens: int


class CVEmbeddingResponse(BaseModel):
    """Response after embedding CV"""
    cvId: int
    totalChunks: int
    embeddedChunks: int
    version: int
    message: str
    processingTime: float


# ===== JD Models =====
class JDEmbeddingRequest(BaseModel):
    """Request to embed Job Description"""
    jdId: int
    hrId: str
    position: str
    jdText: str


class JDEmbeddingResponse(BaseModel):
    """Response after embedding JD"""
    jdId: int
    version: int
    message: str
    processingTime: float


# ===== Search Models =====
class SearchRequest(BaseModel):
    """Search request"""
    query: str
    limit: int = Field(default=10, ge=1, le=100)
    score_threshold: float = Field(default=0.7, ge=0.0, le=1.0)


class SearchResult(BaseModel):
    """Single search result"""
    id: str
    score: float
    payload: dict


class SearchResponse(BaseModel):
    """Search response"""
    query: str
    results: List[SearchResult]
    total: int