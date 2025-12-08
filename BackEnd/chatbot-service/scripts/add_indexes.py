import sys
from pathlib import Path

# Add parent directory to path
sys.path.append(str(Path(__file__).parent.parent))

from app.services.qdrant import qdrant_service
from app.config import get_settings
from qdrant_client.models import PayloadSchemaType

settings = get_settings()

def add_indexes():
    """Add indexes to existing collection"""
    try:
        # Get Qdrant client
        client = qdrant_service.get_client()
        
        # Check if collection exists
        try:
            client.get_collection(settings.JD_COLLECTION_NAME)
            print(f"Collection {settings.JD_COLLECTION_NAME} exists")
        except Exception:
            print(f"Collection {settings.JD_COLLECTION_NAME} does not exist")
            return
        
        # Add index for positionId
        print(f"Creating index for positionId...")
        try:
            client.create_payload_index(
                collection_name=settings.JD_COLLECTION_NAME,
                field_name="positionId",
                field_schema=PayloadSchemaType.INTEGER
            )
            print("Index created for positionId")
        except Exception as e:
            if "already exists" in str(e).lower():
                print("Index for positionId already exists")
            else:
                raise
        
        # Add index for hrId
        print(f"Creating index for hrId...")
        try:
            client.create_payload_index(
                collection_name=settings.JD_COLLECTION_NAME,
                field_name="hrId",
                field_schema=PayloadSchemaType.KEYWORD
            )
            print("Index created for hrId")
        except Exception as e:
            if "already exists" in str(e).lower():
                print("Index for hrId already exists")
            else:
                raise
        
        # Add index for is_latest
        print(f"Creating index for is_latest...")
        try:
            client.create_payload_index(
                collection_name=settings.JD_COLLECTION_NAME,
                field_name="is_latest",
                field_schema=PayloadSchemaType.BOOL
            )
            print("Index created for is_latest")
        except Exception as e:
            if "already exists" in str(e).lower():
                print("Index for is_latest already exists")
            else:
                raise
        
        # Add index for jdId (nếu cần)
        print(f"Creating index for jdId...")
        try:
            client.create_payload_index(
                collection_name=settings.JD_COLLECTION_NAME,
                field_name="jdId",
                field_schema=PayloadSchemaType.INTEGER
            )
            print("Index created for jdId")
        except Exception as e:
            if "already exists" in str(e).lower():
                print("Index for jdId already exists")
            else:
                raise
        
        print("\nAll indexes created successfully!")
        
    except Exception as e:
        print(f"Error creating indexes: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    add_indexes()