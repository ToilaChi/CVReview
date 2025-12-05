import pika
import json
import asyncio
import time
from datetime import datetime
import hashlib
from qdrant_client.models import PointStruct

from app.config import get_settings
from app.services.embedding import embedding_service
from app.services.qdrant import qdrant_service
from app.services.http_client import http_client

settings = get_settings()

JD_QUEUE = "jd.parsed.queue"
JD_DLQ = "jd.parsed.queue.dlq"
JD_EXCHANGE = "jd.parsed.exchange"
JD_DLQ_ROUTING_KEY = "jd.parsed.dlq"


async def embed_jd_from_event(event: dict):
    """
    Embed JD từ RabbitMQ event
    
    Args:
        event: {positionId, jdId, hrId, position}
    """
    start_time = time.time()
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

async def process_message(message: aio_pika.IncomingMessage):
    """Process a single message"""
    async with message.process():
        try:
            event = json.loads(message.body.decode())
            print(f"\n{'='*60}")
            print(f"Received JD event: {event}")
            print(f"{'='*60}")
            
            await embed_jd_from_event(event)
            
            print(f"Acknowledged message for position {event['positionId']}\n")
            
        except Exception as e:
            print(f"Error processing JD event: {e}")
            import traceback
            traceback.print_exc()
            
            raise

async def consume_jd_parsed_events():
    """Consume JD parsed events from RabbitMQ (Async)"""
    
    try:
        #Connect với aio-pika
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
            
            # Declare queue
            queue = await channel.declare_queue(
                JD_QUEUE,
                durable=True,
                arguments={
                    'x-dead-letter-exchange': JD_EXCHANGE,
                    'x-dead-letter-routing-key': JD_DLQ_ROUTING_KEY
                }
            )
            
            # Declare DLQ
            await channel.declare_queue(JD_DLQ, durable=True)
            
            print('JD Embedding Worker started (Async)')
            print(f'Connected to RabbitMQ: {settings.RABBITMQ_HOST}:{settings.RABBITMQ_PORT}')
            print(f'Listening on queue: {JD_QUEUE}')
            print(f'Dead letter queue: {JD_DLQ}\n')
            print('Waiting for messages. Press Ctrl+C to exit...\n')
            
            #Start consuming async
            await queue.consume(process_message)
            
            # Keep running
            await asyncio.Future()
            
    except asyncio.CancelledError:
        print('\nShutting down worker...')
    except Exception as e:
        print(f"Worker error: {e}")
        import traceback
        traceback.print_exc()


def start_consumer():
    """Entry point to start the consumer"""
    try:
        asyncio.run(consume_jd_parsed_events())
    except KeyboardInterrupt:
        print('\nGracefully shutting down...')