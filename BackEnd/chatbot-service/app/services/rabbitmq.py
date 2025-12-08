# import pika
# import json
# import asyncio
# import time
# from datetime import datetime
# import hashlib
# from qdrant_client.models import PointStruct
# import aio_pika
# from aio_pika import ExchangeType

# from app.config import get_settings
# from app.services.embedding import embedding_service
# from app.services.qdrant import qdrant_service
# from app.services.http_client import http_client

# settings = get_settings()

# JD_QUEUE = "jd.parsed.queue"
# JD_DLQ = "jd.parsed.queue.dlq"
# JD_EXCHANGE = "jd.parsed.exchange"
# JD_DLQ_ROUTING_KEY = "jd.parsed.dlq"
# JD_PARSED_ROUTING_KEY = "jd.parsed" 

# def validate_jd_event(event: dict) -> None:
#     """Validate JD event payload"""
#     required_fields = ['positionId', 'hrId', 'position', 'jdText']
    
#     for field in required_fields:
#         if field not in event:
#             raise ValueError(f"Missing required field: {field}")
    
#     position_id = event['positionId']
#     if not isinstance(position_id, int) or position_id <= 0:
#         raise ValueError(f"Invalid positionId: {position_id}")
    
#     jd_text = event['jdText']
#     if not isinstance(jd_text, str):
#         raise ValueError(f"jdText must be string, got {type(jd_text)}")
    
#     if len(jd_text.strip()) < 50:
#         raise ValueError(f"jdText too short: {len(jd_text)} chars")
    
#     if len(jd_text) > 50000:  # 50KB limit
#         raise ValueError(f"jdText too long: {len(jd_text)} chars")
    
#     hr_id = event['hrId']
#     if not isinstance(hr_id, str) or not hr_id.strip():
#         raise ValueError(f"Invalid hrId: {hr_id}")
    
#     position = event['position']
#     if not isinstance(position, str) or not position.strip():
#         raise ValueError(f"Invalid position name: {position}")

# async def embed_jd_from_event(event: dict):
#     """
#     Embed JD từ RabbitMQ event
    
#     Args:
#         event: {positionId, jdId, hrId, position}
#     """
#     start_time = time.time()
    
#     # Validate event payload
#     try:
#         validate_jd_event(event)
#     except ValueError as e:
#         print(f"Invalid event payload: {e}")
#         raise  # Reject message, không retry
    
#     position_id = event['positionId']
    
#     try:
#         print(f"Processing JD embedding for position {position_id}...")
        
#         # 1. Fetch JD text
#         jd_text = event.get('jdText')
        
#         if not jd_text:
#             raise Exception(f"Failed to fetch JD text for position {position_id}")
        
#         if not isinstance(jd_text, str) or not jd_text.strip():
#             raise ValueError("JD text is empty or invalid")
        
#         # 2. Embed
#         print(f"Embedding JD text ({len(jd_text)} chars)...")
#         embedding = embedding_service.embed_text(jd_text)

#         # Delete old embeddings for this position
#         print(f"Deleting old embeddings for position {position_id}...")
#         try:
#             from qdrant_client.models import Filter, FieldCondition, MatchValue
            
#             delete_filter = Filter(
#                 must=[
#                     FieldCondition(
#                         key="positionId",
#                         match=MatchValue(value=position_id)
#                     )
#                 ]
#             )
            
#             qdrant_service.delete_by_filter(
#                 collection_name=settings.JD_COLLECTION_NAME,
#                 filters=delete_filter
#             )
#             print(f"Deleted old embeddings for position {position_id}")
#         except Exception as e:
#             print(f"Warning: Failed to delete old embeddings: {e}")
        
#         # 3. Prepare point
#         version = int(datetime.now().timestamp())
#         point_id_str = f"jd_{position_id}_v{version}"
#         point_id = int(hashlib.md5(point_id_str.encode()).hexdigest(), 16) % (2**63)
        
#         payload = {
#             "jdId": event.get('jdId', position_id),
#             "positionId": position_id,
#             "hrId": event['hrId'],
#             "position": event['position'],
#             "jdText": jd_text,
#             "version": version,
#             "is_latest": True,
#             "createdAt": datetime.now().isoformat(),
#             "textLength": len(jd_text)
#         }
        
#         point = PointStruct(
#             id=point_id,
#             vector=embedding,
#             payload=payload
#         )
        
#         # 4. Upsert
#         print(f"Upserting to Qdrant...")
#         success = qdrant_service.upsert_points(
#             collection_name=settings.JD_COLLECTION_NAME,
#             points=[point]
#         )
        
#         if not success:
#             raise Exception("Failed to upsert to Qdrant")
        
#         processing_time = time.time() - start_time
#         print(f"Successfully embedded JD {position_id} in {processing_time:.2f}s")
        
#     except Exception as e:
#         print(f"Error embedding JD {position_id}: {e}")
#         raise

# async def process_message(message: aio_pika.IncomingMessage):
#     """Process a single message with retry logic"""
#     try:
#         event = json.loads(message.body.decode())
#         print(f"\n{'='*60}")
#         print(f"Received JD event: {event}")
#         print(f"{'='*60}")
        
#         # Check retry count
#         headers = message.headers or {}
#         retry_count = headers.get('x-retry-count', 0)
#         max_retries = 3
        
#         try:
#             await embed_jd_from_event(event)
#             await message.ack()
#             print(f"Acknowledged message for position {event['positionId']}\n")
            
#         except Exception as e:
#             print(f"Error processing JD event (attempt {retry_count + 1}/{max_retries}): {e}")
            
#             if retry_count < max_retries:
#                 # Retry with exponential backoff
#                 new_headers = headers.copy()
#                 new_headers['x-retry-count'] = retry_count + 1
                
#                 await message.nack(requeue=False)
                
#                 # Republish with delay (using TTL + DLX pattern)
#                 delay = 2 ** retry_count * 1000  # 1s, 2s, 4s
#                 print(f"Requeueing with {delay}ms delay...")
                
#             else:
#                 # Max retries reached, send to DLQ
#                 print(f"Max retries reached, sending to DLQ")
#                 await message.nack(requeue=False)
            
#             import traceback
#             traceback.print_exc()
            
#     except json.JSONDecodeError as e:
#         print(f"Invalid JSON in message: {e}")
#         await message.nack(requeue=False)
#     except Exception as e:
#         print(f"Unexpected error in message processing: {e}")
#         await message.nack(requeue=False)

# async def consume_jd_parsed_events():
#     """Consume JD parsed events from RabbitMQ (Async)"""
    
#     try:
#         connection = await aio_pika.connect_robust(
#             host=settings.RABBITMQ_HOST,
#             port=settings.RABBITMQ_PORT,
#             login=settings.RABBITMQ_USER,
#             password=settings.RABBITMQ_PASSWORD,
#             heartbeat=600
#         )
        
#         async with connection:
#             channel = await connection.channel()
#             await channel.set_qos(prefetch_count=1)
            
#             # Declare exchange
#             exchange = await channel.declare_exchange(
#                 JD_EXCHANGE,
#                 ExchangeType.DIRECT,
#                 durable=True
#             )
            
#             # Declare DLQ first
#             dlq = await channel.declare_queue(JD_DLQ, durable=True)
#             await dlq.bind(exchange, routing_key=JD_DLQ_ROUTING_KEY)
            
#             # Declare main queue with DLX and retry settings
#             queue = await channel.declare_queue(
#                 JD_QUEUE,
#                 durable=True,
#                 arguments={
#                     'x-dead-letter-exchange': JD_EXCHANGE,
#                     'x-dead-letter-routing-key': JD_DLQ_ROUTING_KEY,
#                 }
#             )

#             # Bind queue to exchange
#             await queue.bind(exchange, routing_key=JD_PARSED_ROUTING_KEY)  
            
#             print('JD Embedding Worker started (Async)')
#             print(f'Connected to RabbitMQ: {settings.RABBITMQ_HOST}:{settings.RABBITMQ_PORT}')
#             print(f'Listening on queue: {JD_QUEUE}')
#             print(f'Dead letter queue: {JD_DLQ}\n')
#             print('Waiting for messages. Press Ctrl+C to exit...\n')
            
#             await queue.consume(process_message)
#             await asyncio.Future()
            
#     except asyncio.CancelledError:
#         print('\nShutting down worker...')
#     except Exception as e:
#         print(f"Worker error: {e}")
#         import traceback
#         traceback.print_exc()


# def start_consumer():
#     """Entry point to start the consumer"""
#     try:
#         asyncio.run(consume_jd_parsed_events())
#         asyncio.run(consume_cv_chunked_events())
#     except KeyboardInterrupt:
#         print('\nGracefully shutting down...')

# # ============================================================
# # CV CHUNKED FLOW - Consumer for CV embedding
# # ============================================================

# CV_CHUNKED_QUEUE = "cv.chunked.queue"
# CV_CHUNKED_DLQ = "cv.chunked.queue.dlq"
# CV_CHUNKED_EXCHANGE = "cv.chunked.exchange"
# CV_CHUNKED_ROUTING_KEY = "cv.chunked"
# CV_CHUNKED_DLQ_ROUTING_KEY = "cv.chunked.dlq"


# def validate_cv_chunked_event(event: dict) -> None:
#     """Validate CV chunked event payload"""
#     required_fields = ['cvId', 'chunks', 'totalChunks']
    
#     for field in required_fields:
#         if field not in event:
#             raise ValueError(f"Missing required field: {field}")
    
#     cv_id = event['cvId']
#     if not isinstance(cv_id, int) or cv_id <= 0:
#         raise ValueError(f"Invalid cvId: {cv_id}")
    
#     chunks = event['chunks']
#     if not isinstance(chunks, list):
#         raise ValueError(f"chunks must be list, got {type(chunks)}")
    
#     if len(chunks) == 0:
#         raise ValueError(f"chunks list is empty for CV {cv_id}")
    
#     total_chunks = event['totalChunks']
#     if not isinstance(total_chunks, int) or total_chunks != len(chunks):
#         raise ValueError(f"totalChunks mismatch: expected {len(chunks)}, got {total_chunks}")
    
#     # Validate first chunk structure (sample check)
#     first_chunk = chunks[0]
#     required_chunk_fields = ['chunkIndex', 'chunkText', 'section']
#     for field in required_chunk_fields:
#         if field not in first_chunk:
#             raise ValueError(f"Missing required chunk field: {field}")


# async def embed_cv_from_event(event: dict):
#     """
#     Embed CV từ RabbitMQ event
    
#     Args:
#         event: {cvId, candidateId, hrId, position, chunks, totalChunks, totalTokens}
#     """
#     start_time = time.time()
    
#     # Validate event payload
#     try:
#         validate_cv_chunked_event(event)
#     except ValueError as e:
#         print(f"Invalid CV event payload: {e}")
#         raise  # Reject message, không retry
    
#     cv_id = event['cvId']
#     chunks = event['chunks']
    
#     try:
#         print(f"Processing CV embedding for CV {cv_id}...")
#         print(f"Total chunks: {len(chunks)}")
        
#         # 1. Extract chunk texts
#         chunk_texts = [chunk['chunkText'] for chunk in chunks]
        
#         # Validate chunk texts
#         if not all(isinstance(text, str) and text.strip() for text in chunk_texts):
#             raise ValueError("Some chunks have empty or invalid text")
        
#         # 2. Embed all chunks in batch
#         print(f"Embedding {len(chunk_texts)} chunks...")
#         embeddings = embedding_service.embed_batch(chunk_texts, show_progress=True)
        
#         if len(embeddings) != len(chunks):
#             raise Exception(f"Embedding count mismatch: expected {len(chunks)}, got {len(embeddings)}")
        
#         # 3. Delete old embeddings for this CV (nếu có)
#         print(f"Deleting old embeddings for CV {cv_id}...")
#         try:
#             from qdrant_client.models import Filter, FieldCondition, MatchValue
            
#             delete_filter = Filter(
#                 must=[
#                     FieldCondition(
#                         key="cvId",
#                         match=MatchValue(value=cv_id)
#                     )
#                 ]
#             )
            
#             qdrant_service.delete_by_filter(
#                 collection_name=settings.CV_COLLECTION_NAME,
#                 filters=delete_filter
#             )
#             print(f"Deleted old embeddings for CV {cv_id}")
#         except Exception as e:
#             print(f"Warning: Failed to delete old embeddings: {e}")
        
#         # 4. Prepare points
#         version = int(datetime.now().timestamp())
#         points = []
        
#         for chunk, embedding in zip(chunks, embeddings):
#             chunk_index = chunk.get('chunkIndex', 0)
#             point_id_str = f"cv_{cv_id}_chunk_{chunk_index}_v{version}"
#             point_id = int(hashlib.md5(point_id_str.encode()).hexdigest(), 16) % (2**63)
            
#             payload = {
#                 # Core identifiers
#                 "cvId": cv_id,
#                 "candidateId": chunk.get("candidateId"),
#                 "hrId": chunk.get("hrId"),
#                 "position": chunk.get("position", ""),
                
#                 # Chunk info
#                 "section": chunk.get("section", ""),
#                 "chunkIndex": chunk_index,
#                 "chunkText": chunk.get("chunkText", ""),
                
#                 # Metadata
#                 "skills": chunk.get("skills", []),
#                 "experienceYears": chunk.get("experienceYears"),
#                 "seniorityLevel": chunk.get("seniorityLevel", ""),
#                 "email": chunk.get("email", ""),
#                 "companies": chunk.get("companies", []),
#                 "degrees": chunk.get("degrees", []),
#                 "dateRanges": chunk.get("dateRanges", []),
                
#                 # Version tracking
#                 "version": version,
#                 "is_latest": True,
#                 "createdAt": chunk.get("createdAt", datetime.now().isoformat()),
                
#                 # Stats
#                 "words": chunk.get("words", 0),
#                 "tokensEstimate": chunk.get("tokensEstimate", 0),
#                 "cvStatus": chunk.get("cvStatus", "PARSED"),
#                 "sourceType": chunk.get("sourceType", "")
#             }
            
#             points.append(PointStruct(
#                 id=point_id,
#                 vector=embedding,
#                 payload=payload
#             ))
        
#         # 5. Upsert to Qdrant in batches
#         print(f"Upserting {len(points)} points to Qdrant...")
#         batch_size = settings.BATCH_SIZE
        
#         for i in range(0, len(points), batch_size):
#             batch = points[i:i + batch_size]
#             success = qdrant_service.upsert_points(
#                 collection_name=settings.CV_COLLECTION_NAME,
#                 points=batch
#             )
            
#             if not success:
#                 raise Exception(f"Failed to upsert batch {i//batch_size + 1}")
            
#             print(f"Upserted batch {i//batch_size + 1}/{(len(points)-1)//batch_size + 1}")
        
#         processing_time = time.time() - start_time
#         print(f"Successfully embedded CV {cv_id} ({len(points)} chunks) in {processing_time:.2f}s")
        
#     except Exception as e:
#         print(f"Error embedding CV {cv_id}: {e}")
#         raise


# async def process_cv_message(message: aio_pika.IncomingMessage):
#     """Process a single CV chunked message with retry logic"""
#     try:
#         event = json.loads(message.body.decode())
#         print(f"\n{'='*60}")
#         print(f"Received CV chunked event: CV ID {event.get('cvId')}, Chunks: {event.get('totalChunks')}")
#         print(f"{'='*60}")
        
#         # Check retry count
#         headers = message.headers or {}
#         retry_count = headers.get('x-retry-count', 0)
#         max_retries = 3
        
#         try:
#             await embed_cv_from_event(event)
#             await message.ack()
#             print(f"Acknowledged message for CV {event['cvId']}\n")
            
#         except Exception as e:
#             print(f"Error processing CV event (attempt {retry_count + 1}/{max_retries}): {e}")
            
#             if retry_count < max_retries:
#                 # Retry with exponential backoff
#                 print(f"Will retry with backoff...")
#                 await message.nack(requeue=False)
#             else:
#                 # Max retries reached, send to DLQ
#                 print(f"Max retries reached, sending to DLQ")
#                 await message.nack(requeue=False)
            
#             import traceback
#             traceback.print_exc()
            
#     except json.JSONDecodeError as e:
#         print(f"Invalid JSON in CV message: {e}")
#         await message.nack(requeue=False)
#     except Exception as e:
#         print(f"Unexpected error in CV message processing: {e}")
#         await message.nack(requeue=False)


# async def consume_cv_chunked_events():
#     """Consume CV chunked events from RabbitMQ (Async)"""
    
#     try:
#         connection = await aio_pika.connect_robust(
#             host=settings.RABBITMQ_HOST,
#             port=settings.RABBITMQ_PORT,
#             login=settings.RABBITMQ_USER,
#             password=settings.RABBITMQ_PASSWORD,
#             heartbeat=600
#         )
        
#         async with connection:
#             channel = await connection.channel()
#             await channel.set_qos(prefetch_count=1)
            
#             # Declare exchange
#             exchange = await channel.declare_exchange(
#                 CV_CHUNKED_EXCHANGE,
#                 ExchangeType.DIRECT,
#                 durable=True
#             )
            
#             # Declare DLQ first
#             dlq = await channel.declare_queue(CV_CHUNKED_DLQ, durable=True)
#             await dlq.bind(exchange, routing_key=CV_CHUNKED_DLQ_ROUTING_KEY)
            
#             # Declare main queue with DLX and retry settings
#             queue = await channel.declare_queue(
#                 CV_CHUNKED_QUEUE,
#                 durable=True,
#                 arguments={
#                     'x-dead-letter-exchange': CV_CHUNKED_EXCHANGE,
#                     'x-dead-letter-routing-key': CV_CHUNKED_DLQ_ROUTING_KEY,
#                 }
#             )

#             # Bind queue to exchange
#             await queue.bind(exchange, routing_key=CV_CHUNKED_ROUTING_KEY)
            
#             print('CV Embedding Worker started (Async)')
#             print(f'Connected to RabbitMQ: {settings.RABBITMQ_HOST}:{settings.RABBITMQ_PORT}')
#             print(f'Listening on queue: {CV_CHUNKED_QUEUE}')
#             print(f'Dead letter queue: {CV_CHUNKED_DLQ}\n')
#             print('Waiting for CV messages. Press Ctrl+C to exit...\n')
            
#             await queue.consume(process_cv_message)
#             await asyncio.Future()
            
#     except asyncio.CancelledError:
#         print('\nShutting down CV worker...')
#     except Exception as e:
#         print(f"CV Worker error: {e}")
#         import traceback
#         traceback.print_exc()