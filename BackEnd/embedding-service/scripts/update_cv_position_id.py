import sys
import mysql.connector
from pathlib import Path

# Add parent directory to path
sys.path.append(str(Path(__file__).parent.parent))

from qdrant_client import QdrantClient
from qdrant_client.models import PointStruct, PointsList, Filter
from app.config import get_settings

settings = get_settings()

def update_qdrant_position_ids():
    # 1. Connect to MySQL (Update credentials to match your local setup)
    print("Connecting to MySQL...")
    try:
        db = mysql.connector.connect(
            host="mysql-cvre....cloud.com",
            user="avnadmin",
            port=19902,
            password="AV..b",  # Thay đổi password ở đây
            database="recruitment_db"  # Thay đổi db name nếu khác
        )
        cursor = db.cursor(dictionary=True)
    except Exception as e:
        print(f"Could not connect to MySQL: {e}")
        return

    # 2. Extract mapped position_ids for each candidate_cv id
    cursor.execute("SELECT id, position_id FROM candidate_cv WHERE position_id IS NOT NULL")
    cv_to_position = {}
    for row in cursor.fetchall():
        cv_to_position[row['id']] = row['position_id']
    
    print(f"Found {len(cv_to_position)} CVs with positionId in DB.")
    db.close()

    if len(cv_to_position) == 0:
        print("No CVs need updating.")
        return

    # 3. Connect to Qdrant
    print("\nConnecting to Qdrant...")
    if settings.QDRANT_USE_CLOUD:
        client = QdrantClient(
            url=settings.QDRANT_URL,
            api_key=settings.QDRANT_API_KEY
        )
    else:
        client = QdrantClient(
            host=settings.QDRANT_HOST,
            port=settings.QDRANT_PORT
        )
    print("Connected to Qdrant.")

    # 4. Scroll through all points in cv_embeddings and update their payloads
    print("\nUpdating points in cv_embeddings...")
    collection_name = settings.CV_COLLECTION_NAME
    
    offset = None
    updated_count = 0
    skipped_count = 0
    
    while True:
        # Fetch batches of 100
        result, offset = client.scroll(
            collection_name=collection_name,
            limit=100,
            offset=offset,
            with_payload=True,
            with_vectors=False
        )
        
        if not result:
            break
            
        for point in result:
            payload = point.payload
            cv_id = payload.get("cvId")
            
            # Check if we have a positionId mapped for this cvId
            if cv_id and cv_id in cv_to_position:
                db_position_id = cv_to_position[cv_id]
                
                # Check if it's already there and correct to avoid redundant updates
                if payload.get("positionId") != db_position_id:
                    client.set_payload(
                        collection_name=collection_name,
                        payload={"positionId": db_position_id},
                        points=[point.id]
                    )
                    updated_count += 1
                else:
                    skipped_count += 1
            else:
                skipped_count += 1

        if offset is None:
            break

    print(f"\nDone! Updated {updated_count} chunks, skipped {skipped_count} chunks.")

if __name__ == "__main__":
    update_qdrant_position_ids()
