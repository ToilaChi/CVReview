from fastapi import APIRouter, HTTPException
from qdrant_client.models import Filter, FieldCondition, MatchValue

from app.services.qdrant import qdrant_service
from app.config import get_settings

settings = get_settings()
router = APIRouter()


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