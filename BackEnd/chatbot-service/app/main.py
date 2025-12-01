from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager

from app.config import get_settings
from app.services.embedding import embedding_service
from app.services.qdrant import qdrant_service

settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup & Shutdown handlers"""

    # ===== STARTUP =====
    print(f"{settings.APP_NAME} v{settings.VERSION} starting...")

    if settings.QDRANT_USE_CLOUD:
        print(f"Qdrant Cloud: {settings.QDRANT_URL}")
    else:
        print(f"Qdrant Local: {settings.QDRANT_HOST}:{settings.QDRANT_PORT}")

    print("Embedding model will be loaded on first request")

    # Test Qdrant connection
    print("\nTesting Qdrant connection...")
    qdrant_service.test_connection()

    yield

    # ===== SHUTDOWN =====
    print("Shutting down embedding service...")


app = FastAPI(
    title=settings.APP_NAME,
    version=settings.VERSION,
    description="Embedding service for CV Review system",
    debug=settings.DEBUG,
    lifespan=lifespan,
)


# CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Adjust in production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/")
async def root():
    return {
        "service": settings.APP_NAME,
        "version": settings.VERSION,
        "status": "running",
        "model": settings.EMBEDDING_MODEL_NAME,
        "dimension": settings.EMBEDDING_DIMENSION,
        "model_loaded": embedding_service.is_model_loaded(),
    }


@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "model_loaded": embedding_service.is_model_loaded(),
        "qdrant_mode": "cloud" if settings.QDRANT_USE_CLOUD else "local",
        "qdrant_url": settings.QDRANT_URL
        if settings.QDRANT_USE_CLOUD
        else f"{settings.QDRANT_HOST}:{settings.QDRANT_PORT}",
    }


@app.get("/collections/info")
async def get_collections_info():
    cv_info = qdrant_service.get_collection_info(settings.CV_COLLECTION_NAME)
    jd_info = qdrant_service.get_collection_info(settings.JD_COLLECTION_NAME)
    return {"cv_collection": cv_info, "jd_collection": jd_info}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8084,
        reload=settings.DEBUG,
    )
