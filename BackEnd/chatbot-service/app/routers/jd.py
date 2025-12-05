from fastapi import APIRouter, HTTPException, Header
from typing import Optional
import time
import traceback
import hashlib
from datetime import datetime
from qdrant_client.models import PointStruct

from app.models.schemas import JDEmbeddingRequest, JDEmbeddingResponse
from app.services.embedding import embedding_service
from app.services.qdrant import qdrant_service
from app.services.http_client import http_client
from app.config import get_settings

settings = get_settings()
router = APIRouter()


def generate_version() -> int:
    """Generate version number based on timestamp"""
    return int(datetime.now().timestamp())


@router.post("/embed/{position_id}", response_model=JDEmbeddingResponse)
async def embed_jd(
    position_id: int, 
    request: JDEmbeddingRequest,
    authorization: Optional[str] = Header(None)
):
    """
    Embed Job Description
    
    Args:
        position_id: Position/JD ID
        request: JD embedding request with metadata
        authorization: Authorization header (Bearer token)
        
    Returns:
        JDEmbeddingResponse with embedding details
    """
    start_time = time.time()
    
    try:
        # Set auth token if provided
        if authorization:
            # Extract token from "Bearer <token>"
            token = authorization.replace("Bearer ", "").strip()
            http_client.set_auth_token(token)
        
        # 1. Fetch JD text from recruitment service
        jd_text = await http_client.get_jd_text(position_id)
        
        if not jd_text:
            raise HTTPException(
                status_code=404,
                detail=f"Failed to fetch JD text for position {position_id}"
            )
        
        # Validate and clean jd_text
        if not isinstance(jd_text, str):
            raise ValueError(f"JD text must be a string, got {type(jd_text).__name__}")
        
        jd_text = jd_text.strip()
        if not jd_text:
            raise ValueError("JD text is empty after stripping whitespace")
        
        # 2. Embed the entire JD text (no chunking)
        print(f"Embedding JD for position {position_id}...")
        embedding = embedding_service.embed_text(jd_text)
        
        # 3. Prepare point for Qdrant
        version = generate_version()
        # Generate point_id as unsigned integer (hash-based)
        point_id_str = f"jd_{position_id}_v{version}"
        point_id = int(hashlib.md5(point_id_str.encode()).hexdigest(), 16) % (2**63)
        
        payload = {
            # Core identifiers
            "jdId": request.jdId,
            "positionId": position_id,
            "hrId": request.hrId,
            "position": request.position,
            "point_id_str": point_id_str,  # Store original string for reference
            
            # JD content
            "jdText": jd_text,
            
            # Version tracking
            "version": version,
            "is_latest": True,
            "createdAt": datetime.now().isoformat(),
            
            # Stats
            "textLength": len(jd_text)
        }
        
        point = PointStruct(
            id=point_id,
            vector=embedding,
            payload=payload
        )
        
        # 4. Upsert to Qdrant
        print(f"Upserting JD to Qdrant...")
        success = qdrant_service.upsert_points(
            collection_name=settings.JD_COLLECTION_NAME,
            points=[point]
        )
        
        if not success:
            raise HTTPException(
                status_code=500,
                detail="Failed to upsert JD to Qdrant"
            )
        
        processing_time = time.time() - start_time
        
        return JDEmbeddingResponse(
            jdId=request.jdId,
            version=version,
            message=f"Successfully embedded JD for position {position_id}",
            processingTime=round(processing_time, 2)
        )
        
    except HTTPException:
        raise
    except Exception as e:
        print(f"Error embedding JD {position_id}: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Error embedding JD: {str(e)}"
        )

@router.put("/re-embed/{position_id}", response_model=JDEmbeddingResponse)
async def re_embed_jd(
    position_id: int, 
    request: JDEmbeddingRequest,
    authorization: Optional[str] = Header(None)
):
    """
    Re-embed JD (creates new version, keeps old embeddings)
    
    Args:
        position_id: Position/JD ID to re-embed
        request: JD embedding request with metadata
        authorization: Authorization header (Bearer token)
        
    Returns:
        JDEmbeddingResponse with new version
    """
    # Same logic as embed_jd, will create new version automatically
    return await embed_jd(position_id, request, authorization)


@router.delete("/{position_id}")
async def delete_jd_embeddings(position_id: int):
    """
    Delete all embeddings for a JD (all versions)
    
    Args:
        position_id: Position/JD ID to delete
        
    Returns:
        Success message
    """
    try:
        from qdrant_client.models import Filter, FieldCondition, MatchValue
        
        # Delete all points with this positionId
        filter_condition = Filter(
            must=[
                FieldCondition(
                    key="positionId",
                    match=MatchValue(value=position_id)
                )
            ]
        )
        
        success = qdrant_service.delete_by_filter(
            collection_name=settings.JD_COLLECTION_NAME,
            filters=filter_condition
        )
        
        if not success:
            raise HTTPException(
                status_code=500,
                detail=f"Failed to delete embeddings for JD {position_id}"
            )
        
        return {
            "message": f"Successfully deleted all embeddings for JD {position_id}",
            "positionId": position_id
        }
        
    except HTTPException:
        raise
    except Exception as e:
        print(f"Error deleting JD {position_id}: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Error deleting JD embeddings: {str(e)}"
        )