"""
LangGraph workflow for career counseling chatbot
Orchestrates: Intent Classification → Retrieval → LLM Reasoning → Response
"""

from typing import TypedDict, Literal, Optional, List, Dict, Any
from langgraph.graph import StateGraph, END
from langchain_google_genai import ChatGoogleGenerativeAI
from langchain_core.messages import SystemMessage, HumanMessage

from app.rag.intent import intent_classifier
from app.rag.prompts import get_prompt_for_intent
from app.services.retriever import retriever
from app.config import get_settings

settings = get_settings()


# ============================================================
# STATE DEFINITION
# ============================================================

class ChatState(TypedDict):
    """State passed between graph nodes"""
    
    # Input
    query: str
    candidate_id: Optional[str]
    cv_id: Optional[int]
    jd_id: Optional[int]
    conversation_history: Optional[List[Dict[str, str]]]
    
    # Processing
    intent: Literal["jd_search", "jd_analysis", "general"]
    intent_confidence: float
    
    # Retrieved context
    cv_context: List[Dict[str, Any]]
    jd_context: List[Dict[str, Any]]
    retrieval_stats: Dict[str, Any]
    
    # LLM
    system_prompt: str
    user_prompt: str
    llm_response: str
    
    # Output
    final_answer: str
    metadata: Dict[str, Any]


# ============================================================
# GRAPH NODES
# ============================================================

def classify_intent_node(state: ChatState) -> ChatState:
    """
    Node 1: Classify user intent
    """
    query = state["query"]
    
    # Classify
    result = intent_classifier.classify(query)
    
    print(f"Intent: {result['intent']} (confidence: {result['confidence']:.2f})")
    
    state["intent"] = result["intent"]
    state["intent_confidence"] = result["confidence"]
    
    return state


async def retrieve_context_node(state: ChatState) -> ChatState:
    """
    Node 2: Retrieve relevant context based on intent
    """
    query = state["query"]
    intent = state["intent"]
    
    print(f"Retrieving context for intent: {intent}")
    
    # Retrieve based on intent
    result = await retriever.retrieve_for_intent(
        query=query,
        intent=intent,
        candidate_id=state.get("candidate_id"),
        cv_id=state.get("cv_id"),
        jd_id=state.get("jd_id"),
        top_k=5 if intent == "cv_analysis" else 3,
        score_threshold=0.5
    )
    
    # Update state
    state["cv_context"] = result.get("cv_context", [])
    state["jd_context"] = result.get("jd_context", [])
    state["retrieval_stats"] = result.get("retrieval_stats", {})
    
    print(f"Retrieved: {len(state['cv_context'])} CV chunks, {len(state['jd_context'])} JDs")
    
    return state


def build_prompts_node(state: ChatState) -> ChatState:
    """
    Node 3: Build prompts from context
    """
    query = state["query"]
    intent = state["intent"]
    cv_context = state["cv_context"]
    jd_context = state["jd_context"]
    
    print(f"Building prompts for intent: {intent}")
    
    # Get appropriate prompts
    system_prompt, user_prompt = get_prompt_for_intent(
        intent=intent,
        query=query,
        cv_context=cv_context,
        jd_context=jd_context
    )
    
    state["system_prompt"] = system_prompt
    state["user_prompt"] = user_prompt
    
    return state


async def llm_reasoning_node(state: ChatState) -> ChatState:
    """
    Node 4: LLM reasoning with context (Gemini)
    """
    system_prompt = state["system_prompt"]
    user_prompt = state["user_prompt"]

    print(f"Calling Gemini ({settings.GEMINI_MODEL})...")

    llm = ChatGoogleGenerativeAI(
        model=settings.GEMINI_MODEL,
        temperature=settings.GEMINI_TEMPERATURE,
        max_output_tokens=settings.GEMINI_MAX_TOKENS,
        google_api_key=settings.GEMINI_API_KEY
    )

    messages = [
        SystemMessage(content=system_prompt),
        HumanMessage(content=user_prompt)
    ]

    response = await llm.ainvoke(messages)

    state["llm_response"] = response.content

    print(f"Gemini response received ({len(response.content)} chars)")

    return state


def format_response_node(state: ChatState) -> ChatState:
    """
    Node 5: Format final response
    """
    llm_response = state["llm_response"]
    
    # For now, just pass through
    # Can add post-processing here if needed
    state["final_answer"] = llm_response
    
    # Add metadata
    state["metadata"] = {
        "intent": state["intent"],
        "intent_confidence": state["intent_confidence"],
        "cv_chunks_used": len(state["cv_context"]),
        "jd_docs_used": len(state["jd_context"]),
        "retrieval_stats": state["retrieval_stats"]
    }
    
    return state


# ============================================================
# CONDITIONAL ROUTING
# ============================================================

def should_retrieve(state: ChatState) -> Literal["retrieve", "skip_retrieve"]:
    """
    Decide if retrieval is needed based on intent
    """
    intent = state["intent"]
    
    # Luôn retrieve cho jd_search và jd_analysis
    if intent in ["jd_search", "jd_analysis"]:
        return "retrieve"
    
    # Skip cho general với confidence thấp
    if intent == "general" and state["intent_confidence"] < 0.5:
        return "skip_retrieve"
    
    return "retrieve"


# ============================================================
# GRAPH CONSTRUCTION
# ============================================================

def create_career_counselor_graph():
    """
    Create the LangGraph workflow
    
    Flow:
    1. Classify Intent
    2. (Conditional) Retrieve Context
    3. Build Prompts
    4. LLM Reasoning
    5. Format Response
    """
    
    # Create graph
    workflow = StateGraph(ChatState)
    
    # Add nodes
    workflow.add_node("classify_intent", classify_intent_node)
    workflow.add_node("retrieve_context", retrieve_context_node)
    workflow.add_node("build_prompts", build_prompts_node)
    workflow.add_node("llm_reasoning", llm_reasoning_node)
    workflow.add_node("format_response", format_response_node)
    
    # Define flow
    workflow.set_entry_point("classify_intent")
    
    # Conditional edge after intent classification
    workflow.add_conditional_edges(
        "classify_intent",
        should_retrieve,
        {
            "retrieve": "retrieve_context",
            "skip_retrieve": "build_prompts"
        }
    )
    
    # Linear flow after retrieval
    workflow.add_edge("retrieve_context", "build_prompts")
    workflow.add_edge("build_prompts", "llm_reasoning")
    workflow.add_edge("llm_reasoning", "format_response")
    workflow.add_edge("format_response", END)
    
    # Compile
    app = workflow.compile()
    
    return app


# ============================================================
# CONVENIENCE WRAPPER
# ============================================================

class CareerChatbot:
    """
    High-level wrapper for the career counseling chatbot
    """
    
    def __init__(self):
        self.graph = create_career_counselor_graph()
    
    async def chat(
        self,
        query: str,
        candidate_id: Optional[str] = None,
        cv_id: Optional[int] = None,
        jd_id: Optional[int] = None,
        conversation_history: Optional[List[Dict[str, str]]] = None
    ) -> Dict[str, Any]:
        """
        Main chat interface
        
        Args:
            query: User question
            candidate_id: Optional candidate UUID
            cv_id: Optional specific CV ID
            jd_id: Optional specific JD ID
            
        Returns:
            Dict with answer and metadata
        """
        # Initialize state
        initial_state = {
            "query": query,
            "candidate_id": candidate_id,
            "cv_id": cv_id,
            "jd_id": jd_id,
            "conversation_history": conversation_history,
            "cv_context": [],
            "jd_context": [],
            "retrieval_stats": {},
        }
        
        # Run graph
        print(f"\n{'='*60}")
        print(f"Processing query: {query[:50]}...")
        print(f"{'='*60}\n")
        
        final_state = await self.graph.ainvoke(initial_state)
        
        print(f"\n{'='*60}")
        print(f"Query processed successfully")
        print(f"{'='*60}\n")
        
        return {
            "answer": final_state["final_answer"],
            "metadata": final_state["metadata"]
        }


# Global instance
chatbot = CareerChatbot()


# # ============================================================
# # TESTING
# # ============================================================

# if __name__ == "__main__":
#     import asyncio
    
#     async def test():
#         bot = CareerChatbot()
        
#         # Test query
#         result = await bot.chat(
#             query="What Python skills does this candidate have?",
#             candidate_id="02e0d5f5-bf95-11f0-a427-de261a5dec2c"
#         )
        
#         print("\n" + "="*80)
#         print("FINAL ANSWER:")
#         print("="*80)
#         print(result["answer"])
#         print("\n" + "="*80)
#         print("METADATA:")
#         print("="*80)
#         print(result["metadata"])
    
#     asyncio.run(test())