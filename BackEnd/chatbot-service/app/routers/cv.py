from fastapi import APIRouter, HTTPException, BackgroundTasks, Header
from typing import List, Optional
import time
from datetime import datetime
from qdrant_client.models import PointStruct

from app.models.schemas import CVEmbeddingResponse
from app.services.embedding import embedding_service
from app.services.qdrant import qdrant_service
from app.services.http_client import http_client
from app.config import get_settings

settings = get_settings()
router = APIRouter()


def generate_version() -> int:
    """Generate version number based on timestamp"""
    return int(datetime.now().timestamp())


@router.post("/embed/{cv_id}", response_model=CVEmbeddingResponse)
async def embed_cv(cv_id: int, authorization: Optional[str] = Header(None)):
    """
    Embed CV by fetching chunks from chunking service
    
    Args:
        cv_id: CV ID to embed
        authorization: Authorization header (Bearer token)
        
    Returns:
        CVEmbeddingResponse with embedding details
    """
    start_time = time.time()
    
    try:
        # Set auth token if provided
        if authorization:
            # Extract token from "Bearer <token>"
            token = authorization.replace("Bearer ", "").strip()
            http_client.set_auth_token(token)
        
        # 1. Fetch CV chunks from chunking service
        cv_data = await http_client.get_cv_chunks(cv_id)
        if not cv_data:
            raise HTTPException(
                status_code=404,
                detail=f"Failed to fetch chunks for CV {cv_id}"
            )
        
        chunks = cv_data.get("chunks", [])
        if not chunks:
            raise HTTPException(
                status_code=400,
                detail=f"No chunks found for CV {cv_id}"
            )
        
        # 2. Extract chunk texts for batch embedding
        chunk_texts = [chunk["chunkText"] for chunk in chunks]
        
        # 3. Embed all chunks in batch
        print(f"Embedding {len(chunk_texts)} chunks...")
        embeddings = embedding_service.embed_batch(chunk_texts, show_progress=True)
        
        # 4. Prepare points for Qdrant
        version = generate_version()
        points = []
        
        for idx, (chunk, embedding) in enumerate(zip(chunks, embeddings)):
            point_id = f"cv_{cv_id}_chunk_{chunk['chunkIndex']}_v{version}"
            
            payload = {
                # Core identifiers
                "cvId": cv_id,
                "hrId": chunk.get("hrId"),
                "candidateId": chunk.get("candidateId"),
                "position": chunk.get("position", ""),
                
                # Chunk info
                "section": chunk.get("section", ""),
                "chunkIndex": chunk.get("chunkIndex", idx),
                "chunkText": chunk.get("chunkText", ""),
                
                # Metadata
                "skills": chunk.get("skills", []),
                "experienceYears": chunk.get("experienceYears"),
                "seniorityLevel": chunk.get("seniorityLevel", ""),
                "email": chunk.get("email", ""),
                
                # Version tracking
                "version": version,
                "is_latest": True,
                "createdAt": chunk.get("createdAt", datetime.now().isoformat()),
                
                # Stats
                "words": chunk.get("words", 0),
                "tokensEstimate": chunk.get("tokensEstimate", 0)
            }
            
            points.append(PointStruct(
                id=point_id,
                vector=embedding,
                payload=payload
            ))
        
        # 5. Mark old versions as not latest
        # (We'll keep old embeddings for versioning, just mark them)
        # Future: implement cleanup for very old versions
        
        # 6. Upsert to Qdrant in batches
        print(f"Upserting {len(points)} points to Qdrant...")
        batch_size = settings.BATCH_SIZE
        
        for i in range(0, len(points), batch_size):
            batch = points[i:i + batch_size]
            success = qdrant_service.upsert_points(
                collection_name=settings.CV_COLLECTION_NAME,
                points=batch
            )
            if not success:
                raise HTTPException(
                    status_code=500,
                    detail=f"Failed to upsert batch {i//batch_size + 1}"
                )
            print(f"Upserted batch {i//batch_size + 1}/{(len(points)-1)//batch_size + 1}")
        
        processing_time = time.time() - start_time
        
        return CVEmbeddingResponse(
            cvId=cv_id,
            totalChunks=len(chunks),
            embeddedChunks=len(points),
            version=version,
            message=f"Successfully embedded {len(points)} chunks",
            processingTime=round(processing_time, 2)
        )
        
    except HTTPException:
        raise
    except Exception as e:
        print(f"Error embedding CV {cv_id}: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Error embedding CV: {str(e)}"
        )


@router.put("/re-embed/{cv_id}", response_model=CVEmbeddingResponse)
async def re_embed_cv(cv_id: int, authorization: Optional[str] = Header(None)):
    """
    Re-embed CV (creates new version, keeps old embeddings)
    
    Args:
        cv_id: CV ID to re-embed
        authorization: Authorization header (Bearer token)
        
    Returns:
        CVEmbeddingResponse with new version
    """
    # Same logic as embed_cv, will create new version automatically
    return await embed_cv(cv_id, authorization)


@router.delete("/{cv_id}")
async def delete_cv_embeddings(cv_id: int):
    """
    Delete all embeddings for a CV (all versions)
    
    Args:
        cv_id: CV ID to delete
        
    Returns:
        Success message
    """
    try:
        from qdrant_client.models import Filter, FieldCondition, MatchValue
        
        # Delete all points with this cvId
        filter_condition = Filter(
            must=[
                FieldCondition(
                    key="cvId",
                    match=MatchValue(value=cv_id)
                )
            ]
        )
        
        success = qdrant_service.delete_by_filter(
            collection_name=settings.CV_COLLECTION_NAME,
            filters=filter_condition
        )
        
        if not success:
            raise HTTPException(
                status_code=500,
                detail=f"Failed to delete embeddings for CV {cv_id}"
            )
        
        return {
            "message": f"Successfully deleted all embeddings for CV {cv_id}",
            "cvId": cv_id
        }
        
    except HTTPException:
        raise
    except Exception as e:
        print(f"Error deleting CV {cv_id}: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Error deleting CV embeddings: {str(e)}"
        )