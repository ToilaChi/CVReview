# test_retriever.py
import asyncio
from app.services.retriever import retriever

async def test():
    result = await retriever.retrieve_for_intent(
        query="What Python skills does this candidate have?",
        intent="cv_analysis",
        candidate_id="02e0d5f5-bf95-11f0-a427-de261a5dec2c",
        top_k=3
    )
    
    print("\n📊 Retrieval Stats:")
    print(result["retrieval_stats"])
    
    print("\n📄 CV Chunks:")
    for i, chunk in enumerate(result["cv_context"][:2], 1):
        print(f"\n{i}. Score: {chunk['score']:.3f}")
        print(f"   Section: {chunk['payload']['section']}")
        print(f"   Text: {chunk['payload']['chunkText'][:100]}...")

asyncio.run(test())