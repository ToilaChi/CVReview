import sys
from pathlib import Path

# Add parent directory to path
sys.path.append(str(Path(__file__).parent.parent))

from app.services.qdrant import qdrant_service
from app.config import get_settings

settings = get_settings()


def init_collections():
    """Initialize CV and JD collections"""
    
    print("Initializing Qdrant Collections...")
    print(f"Vector dimension: {settings.EMBEDDING_DIMENSION}")
    
    # Test connection first
    print("\nTesting Qdrant connection...")
    if not qdrant_service.test_connection():
        print("Failed to connect to Qdrant. Please check your credentials.")
        return False
    
    # Create CV collection
    print(f"\n2Creating CV collection: {settings.CV_COLLECTION_NAME}")
    qdrant_service.create_collection(
        collection_name=settings.CV_COLLECTION_NAME,
        vector_size=settings.EMBEDDING_DIMENSION
    )
    
    # Create JD collection
    print(f"\n3Creating JD collection: {settings.JD_COLLECTION_NAME}")
    qdrant_service.create_collection(
        collection_name=settings.JD_COLLECTION_NAME,
        vector_size=settings.EMBEDDING_DIMENSION
    )
    
    # Verify collections
    print("\n4Verifying collections...")
    cv_info = qdrant_service.get_collection_info(settings.CV_COLLECTION_NAME)
    jd_info = qdrant_service.get_collection_info(settings.JD_COLLECTION_NAME)
    
    if cv_info and jd_info:
        print(f"\nCV Collection: {cv_info}")
        print(f"JD Collection: {jd_info}")
        print("\nAll collections initialized successfully!")
        return True
    else:
        print("\nFailed to verify collections")
        return False


if __name__ == "__main__":
    success = init_collections()
    sys.exit(0 if success else 1)