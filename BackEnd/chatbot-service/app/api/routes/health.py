from fastapi import APIRouter, status
from app.models.chat import HealthResponse
from app.services.embedding import embedding_service
from app.services.qdrant import qdrant_service
from app.config import get_settings

settings = get_settings()

router = APIRouter()


@router.get(
    "/health",
    response_model=HealthResponse,
    status_code=status.HTTP_200_OK,
    summary="Health Check",
    description="Check the health status of the chatbot service and its dependencies"
)
async def health_check():
    """
    Health check endpoint
    
    Returns:
        Service status and component health
    """
    components = {
        "embedding_service": "unknown",
        "qdrant": "unknown",
        "llm": "unknown"
    }
    
    # Check embedding service
    try:
        if embedding_service.is_model_loaded():
            components["embedding_service"] = "ok"
        else:
            # Try to load
            embedding_service._load_model()
            components["embedding_service"] = "ok"
    except Exception as e:
        components["embedding_service"] = f"error: {str(e)}"
    
    # Check Qdrant
    try:
        if qdrant_service.test_connection():
            components["qdrant"] = "ok"
        else:
            components["qdrant"] = "error: connection failed"
    except Exception as e:
        components["qdrant"] = f"error: {str(e)}"
    
    # Check LLM (OpenAI)
    try:
        if settings.OPENAI_API_KEY and len(settings.OPENAI_API_KEY) > 10:
            components["llm"] = "ok"
        else:
            components["llm"] = "error: API key not configured"
    except Exception as e:
        components["llm"] = f"error: {str(e)}"
    
    # Determine overall status
    overall_status = "ok"
    if any("error" in status for status in components.values()):
        overall_status = "degraded"
    if all("error" in status for status in components.values()):
        overall_status = "error"
    
    return HealthResponse(
        status=overall_status,
        service="chatbot-service",
        components=components
    )


@router.get(
    "/health/ready",
    summary="Readiness Check",
    description="Check if the service is ready to accept requests"
)
async def readiness_check():
    """
    Kubernetes readiness probe
    Returns 200 if service is ready, 503 otherwise
    """
    try:
        # Check critical components
        embedding_ready = embedding_service.is_model_loaded()
        qdrant_ready = qdrant_service.test_connection()
        
        if embedding_ready and qdrant_ready:
            return {"status": "ready"}
        else:
            return {"status": "not_ready", "embedding": embedding_ready, "qdrant": qdrant_ready}
    
    except Exception as e:
        return {"status": "error", "detail": str(e)}


@router.get(
    "/health/live",
    summary="Liveness Check",
    description="Check if the service is alive"
)
async def liveness_check():
    """
    Kubernetes liveness probe
    Simple check that service is responding
    """
    return {"status": "alive"}