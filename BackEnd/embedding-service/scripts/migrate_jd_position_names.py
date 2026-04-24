import sys
import os

# Add the parent directory to sys.path so we can import app modules
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from qdrant_client import QdrantClient
from qdrant_client.models import PointStruct
from app.config import get_settings

def migrate_jd_position_names():
    settings = get_settings()
    
    if settings.QDRANT_USE_CLOUD:
        print(f"Connecting to Qdrant Cloud: {settings.QDRANT_URL}")
        client = QdrantClient(
            url=settings.QDRANT_URL,
            api_key=settings.QDRANT_API_KEY,
        )
    else:
        print(f"Connecting to Qdrant Local: {settings.QDRANT_HOST}:{settings.QDRANT_PORT}")
        client = QdrantClient(
            host=settings.QDRANT_HOST,
            port=settings.QDRANT_PORT,
        )

    collection_name = settings.JD_COLLECTION_NAME
    print(f"Migrating collection: {collection_name}")

    count = 0
    updated_count = 0
    
    # Scroll through all points in the JD collection
    points_iterator = client.scroll(
        collection_name=collection_name,
        with_payload=True,
        with_vectors=False,
        limit=100
    )

    while True:
        points, next_page_offset = points_iterator
        
        for point in points:
            count += 1
            payload = point.payload
            
            # Check if we need to migrate
            level = payload.get("level")
            language = payload.get("language")
            old_name = payload.get("positionName", "")
            
            # If level or language exist as separate fields, or if we want to enforce the format
            if level is not None or language is not None:
                # Build the new formatted name
                # Priority: level + language + name
                parts = []
                if level: parts.append(level)
                if language: parts.append(language)
                if old_name: parts.append(old_name)
                
                new_name = " ".join(parts).strip()
                
                print(f"[{count}] Updating ID {point.id}: '{old_name}' -> '{new_name}'")
                
                # Update payload
                new_payload = payload.copy()
                new_payload["positionName"] = new_name
                
                # Remove legacy fields
                if "level" in new_payload: del new_payload["level"]
                if "language" in new_payload: del new_payload["language"]
                
                # Overwrite payload for this point
                client.overwrite_payload(
                    collection_name=collection_name,
                    payload=new_payload,
                    points=[point.id]
                )
                updated_count += 1
            else:
                # Already migrated or missing fields
                pass
        
        if next_page_offset is None:
            break
            
        points_iterator = client.scroll(
            collection_name=collection_name,
            with_payload=True,
            with_vectors=False,
            limit=100,
            offset=next_page_offset
        )

    print(f"\nMigration complete!")
    print(f"Total points scanned: {count}")
    print(f"Total points updated: {updated_count}")

if __name__ == "__main__":
    migrate_jd_position_names()
