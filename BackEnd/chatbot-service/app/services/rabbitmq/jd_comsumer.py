import pika
import json
import asyncio
import time
from datetime import datetime
import hashlib
from qdrant_client.models import PointStruct
import aio_pika
from aio_pika import ExchangeType

from app.config import get_settings
from app.services.embedding import embedding_service
from app.services.qdrant import qdrant_service

settings = get_settings()


# ============================================================
# JD CHUNKED FLOW - Consumer for JD embedding
# ============================================================

JD_QUEUE = "jd.parsed.queue"
JD_DLQ = "jd.parsed.queue.dlq"
JD_EXCHANGE = "jd.parsed.exchange"
JD_DLQ_ROUTING_KEY = "jd.parsed.dlq"
JD_PARSED_ROUTING_KEY = "jd.parsed" 

def validate_jd_event(event: dict) -> None:
    """Validate JD event payload"""
    required_fields = ['positionId', 'hrId', 'position', 'jdText']
    
    for field in required_fields:
        if field not in event:
            raise ValueError(f"Missing required field: {field}")
    
    position_id = event['positionId']
    if not isinstance(position_id, int) or position_id <= 0:
        raise ValueError(f"Invalid positionId: {position_id}")
    
    jd_text = event['jdText']
    if not isinstance(jd_text, str):
        raise ValueError(f"jdText must be string, got {type(jd_text)}")
    
    if len(jd_text.strip()) < 50:
        raise ValueError(f"jdText too short: {len(jd_text)} chars")
    
    if len(jd_text) > 50000:  # 50KB limit
        raise ValueError(f"jdText too long: {len(jd_text)} chars")
    
    hr_id = event['hrId']
    if not isinstance(hr_id, str) or not hr_id.strip():
        raise ValueError(f"Invalid hrId: {hr_id}")
    
    position = event['position']
    if not isinstance(position, str) or not position.strip():
        raise ValueError(f"Invalid position name: {position}")

async def embed_jd_from_event(event: dict):
    """
    Embed JD từ RabbitMQ event
    
    Args:
        event: {positionId, jdId, hrId, position}
    """
    start_time = time.time()
    
    # Validate event payload
    try:
        validate_jd_event(event)
    except ValueError as e:
        print(f"Invalid event payload: {e}")
        raise  # Reject message, không retry
    
    position_id = event['positionId']
    
    try:
        print(f"Processing JD embedding for position {position_id}...")
        
        # 1. Fetch JD text
        jd_text = event.get('jdText')
        
        if not jd_text:
            raise Exception(f"Failed to fetch JD text for position {position_id}")
        
        if not isinstance(jd_text, str) or not jd_text.strip():
            raise ValueError("JD text is empty or invalid")
        
        # 2. Embed
        print(f"Embedding JD text ({len(jd_text)} chars)...")
        embedding = embedding_service.embed_text(jd_text)

        # Delete old embeddings for this position
        print(f"Deleting old embeddings for position {position_id}...")
        try:
            from qdrant_client.models import Filter, FieldCondition, MatchValue
            
            delete_filter = Filter(
                must=[
                    FieldCondition(
                        key="positionId",
                        match=MatchValue(value=position_id)
                    )
                ]
            )
            
            qdrant_service.delete_by_filter(
                collection_name=settings.JD_COLLECTION_NAME,
                filters=delete_filter
            )
            print(f"Deleted old embeddings for position {position_id}")
        except Exception as e:
            print(f"Warning: Failed to delete old embeddings: {e}")
        
        # 3. Prepare point
        version = int(datetime.now().timestamp())
        point_id_str = f"jd_{position_id}_v{version}"
        point_id = int(hashlib.md5(point_id_str.encode()).hexdigest(), 16) % (2**63)
        
        payload = {
            "jdId": event.get('jdId', position_id),
            "positionId": position_id,
            "hrId": event['hrId'],
            "position": event['position'],
            "jdText": jd_text,
            "version": version,
            "is_latest": True,
            "createdAt": datetime.now().isoformat(),
            "textLength": len(jd_text)
        }
        
        point = PointStruct(
            id=point_id,
            vector=embedding,
            payload=payload
        )
        
        # 4. Upsert
        print(f"Upserting to Qdrant...")
        success = qdrant_service.upsert_points(
            collection_name=settings.JD_COLLECTION_NAME,
            points=[point]
        )
        
        if not success:
            raise Exception("Failed to upsert to Qdrant")
        
        processing_time = time.time() - start_time
        print(f"Successfully embedded JD {position_id} in {processing_time:.2f}s")
        
    except Exception as e:
        print(f"Error embedding JD {position_id}: {e}")
        raise

async def process_jd_message(message: aio_pika.IncomingMessage):
    """Process a single message with retry logic"""
    try:
        event = json.loads(message.body.decode())
        print(f"\n{'='*60}")
        print(f"Received JD event: {event}")
        print(f"{'='*60}")
        
        # Check retry count
        headers = message.headers or {}
        retry_count = headers.get('x-retry-count', 0)
        max_retries = 3
        
        try:
            await embed_jd_from_event(event)
            await message.ack()
            print(f"Acknowledged message for position {event['positionId']}\n")
            
        except Exception as e:
            print(f"Error processing JD event (attempt {retry_count + 1}/{max_retries}): {e}")
            
            if retry_count < max_retries:
                # Retry with exponential backoff
                new_headers = headers.copy()
                new_headers['x-retry-count'] = retry_count + 1
                
                await message.nack(requeue=False)
                
                # Republish with delay (using TTL + DLX pattern)
                delay = 2 ** retry_count * 1000  # 1s, 2s, 4s
                print(f"Requeueing with {delay}ms delay...")
                
            else:
                # Max retries reached, send to DLQ
                print(f"Max retries reached, sending to DLQ")
                await message.nack(requeue=False)
            
            import traceback
            traceback.print_exc()
            
    except json.JSONDecodeError as e:
        print(f"Invalid JSON in message: {e}")
        await message.nack(requeue=False)
    except Exception as e:
        print(f"Unexpected error in message processing: {e}")
        await message.nack(requeue=False)

async def consume_jd_parsed_events():
    """Consume JD parsed events from RabbitMQ (Async)"""
    
    try:
        connection = await aio_pika.connect_robust(
            host=settings.RABBITMQ_HOST,
            port=settings.RABBITMQ_PORT,
            login=settings.RABBITMQ_USER,
            password=settings.RABBITMQ_PASSWORD,
            heartbeat=600
        )
        
        async with connection:
            channel = await connection.channel()
            await channel.set_qos(prefetch_count=1)
            
            # Declare exchange
            exchange = await channel.declare_exchange(
                JD_EXCHANGE,
                ExchangeType.DIRECT,
                durable=True
            )
            
            # Declare DLQ first
            dlq = await channel.declare_queue(JD_DLQ, durable=True)
            await dlq.bind(exchange, routing_key=JD_DLQ_ROUTING_KEY)
            
            # Declare main queue with DLX and retry settings
            queue = await channel.declare_queue(
                JD_QUEUE,
                durable=True,
                arguments={
                    'x-dead-letter-exchange': JD_EXCHANGE,
                    'x-dead-letter-routing-key': JD_DLQ_ROUTING_KEY,
                }
            )

            # Bind queue to exchange
            await queue.bind(exchange, routing_key=JD_PARSED_ROUTING_KEY)  
            
            print('JD Embedding Worker started (Async)')
            print(f'Connected to RabbitMQ: {settings.RABBITMQ_HOST}:{settings.RABBITMQ_PORT}')
            print(f'Listening on queue: {JD_QUEUE}')
            print(f'Dead letter queue: {JD_DLQ}\n')
            print('Waiting for JD messages. Press Ctrl+C to exit...\n')
            
            await queue.consume(process_jd_message)
            await asyncio.Future()
            
    except asyncio.CancelledError:
        print('\nShutting down JD worker...')
    except Exception as e:
        print(f"Worker error: {e}")
        import traceback
        traceback.print_exc()

def start_jd_consumer():
    """Entry point to start the consumer"""
    try:
        asyncio.run(consume_jd_parsed_events())
    except KeyboardInterrupt:
        print('\nGracefully shutting down...')
