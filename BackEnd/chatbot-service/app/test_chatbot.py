import asyncio
from app.rag.graph import chatbot

async def test_chatbot():
    """Test the complete chatbot workflow"""
    
    # Test 1: CV Analysis
    print("\n" + "="*80)
    print("TEST 1: CV Analysis")
    print("="*80)
    
    result1 = await chatbot.chat(
        query="What Python and React skills does this candidate have?",
        candidate_id="02e0d5f5-bf95-11f0-a427-de261a5dec2c"
    )
    
    print("\nANSWER:")
    print(result1["answer"])
    print("\nMETADATA:")
    print(result1["metadata"])
    
    # Test 2: JD Search
    print("\n" + "="*80)
    print("TEST 2: JD Search")
    print("="*80)
    
    result2 = await chatbot.chat(
        query="What backend engineer positions do you have?"
    )
    
    print("\nANSWER:")
    print(result2["answer"])
    
    # Test 3: CV-JD Match
    print("\n" + "="*80)
    print("TEST 3: CV-JD Matching")
    print("="*80)
    
    result3 = await chatbot.chat(
        query="How well does this candidate match the position?",
        cv_id=148,
        jd_id=19
    )
    
    print("\nANSWER:")
    print(result3["answer"])

if __name__ == "__main__":
    asyncio.run(test_chatbot())