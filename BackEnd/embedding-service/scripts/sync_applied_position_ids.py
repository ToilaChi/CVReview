"""
Phase 3 — One-time Data Sync Script
====================================
Backfills the `applied_position_ids` array onto existing Candidate CV chunks
in Qdrant by joining MySQL `cv_applications` ↔ `candidate_cvs` tables.

Why this is needed:
  After Phase 3, the retriever filters Candidate CVs via:
      applied_position_ids CONTAINS [position_id]
  Old chunks uploaded before this release don't have this field, so they
  would be invisible to the new filter. This script patches them in-place
  using Qdrant's set_payload API — no re-embedding required.

Usage:
  1.  Activate the embedding-service venv.
  2.  Update MYSQL_* constants below to match your .env values.
  3.  Run from the BackEnd/embedding-service directory:
          python scripts/sync_applied_position_ids.py
  4.  Re-run is safe — it skips chunks that already have the correct data.
"""

import sys
from pathlib import Path
from collections import defaultdict

import mysql.connector

# Allow importing embedding-service app config
sys.path.append(str(Path(__file__).parent.parent))

from qdrant_client import QdrantClient
from app.config import get_settings

# ---------------------------------------------------------------------------
# MySQL connection — change these to match your .env / cloud credentials
# ---------------------------------------------------------------------------
MYSQL_CONFIG = {
    "host":     "mysql-cvreview86-chi12345pham-eb45.k.aivencloud.com",     # DB_URL host
    "port":     19902,
    "user":     "avnadmin",          # DB_USERNAME
    "password": "AVNS....YuBgXb", # DB_PASSWORD  ← CHANGE THIS
    "database": "recruitment_db",
}

# Scroll page size for Qdrant — tune if you hit memory limits
QDRANT_PAGE_SIZE = 200

settings = get_settings()


def _connect_mysql() -> mysql.connector.MySQLConnection:
    print("Connecting to MySQL...")
    try:
        conn = mysql.connector.connect(**MYSQL_CONFIG)
        print(f"  Connected to MySQL at {MYSQL_CONFIG['host']}:{MYSQL_CONFIG['port']}")
        return conn
    except mysql.connector.Error as exc:
        print(f"  ERROR: Could not connect to MySQL: {exc}")
        sys.exit(1)


def _build_cv_to_positions(conn: mysql.connector.MySQLConnection) -> dict[int, list[int]]:
    """
    Build a mapping: master_cv_id (int) → [position_id, ...].

    Query joins:
      cv_applications (application_cv_id, position_id)
        → candidate_cvs (id=application_cv_id, master_cv_id)

    A Candidate can apply to multiple positions, so the result is a list.
    Only CANDIDATE-type rows are included (hrId IS NULL in candidate_cvs).
    """
    print("\nFetching application data from MySQL...")

    # Adjust table/column names if your schema differs.
    query = """
        SELECT
            parent_cv_id  AS master_cv_id,
            position_id   AS position_id
        FROM candidate_cv
        WHERE source_type = 'CANDIDATE'
          AND position_id IS NOT NULL
          AND parent_cv_id IS NOT NULL
    """
    cursor = conn.cursor(dictionary=True)
    try:
        cursor.execute(query)
        rows = cursor.fetchall()
    except mysql.connector.Error as exc:
        print(f"  ERROR running query: {exc}")
        print("  Adjust the query above to match your actual table/column names.")
        conn.close()
        sys.exit(1)
    finally:
        cursor.close()

    cv_to_positions: dict[int, list[int]] = defaultdict(list)
    for row in rows:
        master_cv_id = int(row["master_cv_id"])
        position_id  = int(row["position_id"])
        if position_id not in cv_to_positions[master_cv_id]:
            cv_to_positions[master_cv_id].append(position_id)

    print(f"  Found {len(cv_to_positions)} unique master CVs with applications.")
    total_links = sum(len(v) for v in cv_to_positions.values())
    print(f"  Total CV <-> Position links: {total_links}")
    return dict(cv_to_positions)


def _connect_qdrant() -> QdrantClient:
    print("\nConnecting to Qdrant...")
    if settings.QDRANT_USE_CLOUD:
        client = QdrantClient(url=settings.QDRANT_URL, api_key=settings.QDRANT_API_KEY, timeout=60)
        print(f"  Connected to Qdrant Cloud: {settings.QDRANT_URL}")
    else:
        client = QdrantClient(host=settings.QDRANT_HOST, port=settings.QDRANT_PORT, timeout=60)
        print(f"  Connected to Qdrant Local: {settings.QDRANT_HOST}:{settings.QDRANT_PORT}")
    return client


def _sync_qdrant_payloads(
    client: QdrantClient,
    cv_to_positions: dict[int, list[int]],
) -> None:
    """
    Scroll through all points in cv_embeddings and patch the ones that
    belong to CANDIDATE-type CVs with the correct applied_position_ids list.

    Skips points that:
      - Are not CANDIDATE sourceType (HR CVs don't need this field).
      - Already have the exact correct applied_position_ids value.
    """
    collection = settings.CV_COLLECTION_NAME
    print(f"\nScanning collection '{collection}'...")

    updated_chunks   = 0
    skipped_no_data  = 0
    skipped_up_to_date = 0
    offset = None

    while True:
        results, offset = client.scroll(
            collection_name=collection,
            limit=QDRANT_PAGE_SIZE,
            offset=offset,
            with_payload=True,
            with_vectors=False,
        )

        if not results:
            break

        for point in results:
            payload     = point.payload or {}
            source_type = payload.get("sourceType", "")
            cv_id       = payload.get("cvId")

            # Only patch CANDIDATE-type chunks
            if source_type != "CANDIDATE":
                skipped_no_data += 1
                continue

            if cv_id is None:
                skipped_no_data += 1
                continue

            desired_positions = cv_to_positions.get(int(cv_id))
            if not desired_positions:
                # This candidate CV has no application in DB — leave as empty list
                skipped_no_data += 1
                continue

            current_positions = payload.get("applied_position_ids", None)

            # Skip if already correct (order-independent comparison)
            if (
                isinstance(current_positions, list)
                and set(current_positions) == set(desired_positions)
            ):
                skipped_up_to_date += 1
                continue

            # Patch the payload — keeps all other fields intact
            client.set_payload(
                collection_name=collection,
                payload={"applied_position_ids": desired_positions},
                points=[point.id],
            )
            updated_chunks += 1

        print(
            f"  Progress — updated: {updated_chunks} | "
            f"up-to-date: {skipped_up_to_date} | "
            f"skipped (no data): {skipped_no_data}"
        )

        if offset is None:
            break

    print(
        f"\nSync complete!\n"
        f"  Chunks updated    : {updated_chunks}\n"
        f"  Already up-to-date: {skipped_up_to_date}\n"
        f"  Skipped (no link) : {skipped_no_data}\n"
    )


def main() -> None:
    print("=" * 60)
    print("Phase 3 -- Sync applied_position_ids -> Qdrant")
    print("=" * 60)

    conn = _connect_mysql()
    cv_to_positions = _build_cv_to_positions(conn)
    conn.close()

    if not cv_to_positions:
        print("\nNo data to sync. Exiting.")
        return

    client = _connect_qdrant()
    _sync_qdrant_payloads(client, cv_to_positions)


if __name__ == "__main__":
    main()
