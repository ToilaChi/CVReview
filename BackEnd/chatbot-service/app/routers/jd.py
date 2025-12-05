from fastapi import APIRouter, HTTPException
from qdrant_client.models import Filter, FieldCondition, MatchValue

from app.services.qdrant import qdrant_service
from app.config import get_settings

settings = get_settings()
router = APIRouter()


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


@router.get("/status/{position_id}")
async def get_jd_embedding_status(position_id: int):
    """
    Check if JD has been embedded
    
    Args:
        position_id: Position/JD ID
        
    Returns:
        Embedding status
    """
    try:
        # Search for points with this positionId
        filter_condition = Filter(
            must=[
                FieldCondition(
                    key="positionId",
                    match=MatchValue(value=position_id)
                )
            ]
        )
        
        results = qdrant_service.client.scroll(
            collection_name=settings.JD_COLLECTION_NAME,
            scroll_filter=filter_condition,
            limit=1
        )
        
        embedded = len(results[0]) > 0
        
        return {
            "positionId": position_id,
            "embedded": embedded,
            "message": "JD is embedded" if embedded else "JD is not embedded yet"
        }
        
    except Exception as e:
        print(f"Error checking JD status {position_id}: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Error checking JD status: {str(e)}"
        )