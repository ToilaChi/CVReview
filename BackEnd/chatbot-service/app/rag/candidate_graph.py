"""
LangGraph workflow for candidate chatbot
Orchestrates: Intent Classification → Retrieval → LLM Reasoning → Response
"""

import json
from typing import TypedDict, Literal, Optional, List, Dict, Any
from langgraph.graph import StateGraph, END
from langchain_google_genai import ChatGoogleGenerativeAI
from langchain_core.messages import SystemMessage, HumanMessage, ToolMessage

from app.rag.intent import intent_classifier
from app.rag.prompts import get_prompt_for_intent
from app.services.retriever import retriever
from app.services.recruitment_api import recruitment_api
from app.rag.candidate_tools import CANDIDATE_TOOLS
from app.config import get_settings

settings = get_settings()

class ChatState(TypedDict):
    """State passed between graph nodes"""
    
    # Input
    session_id: str
    query: str
    candidate_id: str
    cv_id: Optional[int]
    jd_id: Optional[int]
    
    # Session
    conversation_history: List[Dict[str, Any]]
    active_position_ids: List[int]
    
    # Processing
    intent: Literal["jd_search", "jd_analysis", "cv_analysis", "general"]
    intent_confidence: float
    domain: str
    
    # Retrieved context
    cv_context: List[Dict[str, Any]]
    jd_context: List[Dict[str, Any]]
    retrieval_stats: Dict[str, Any]
    
    # Scoring
    scored_jobs: Optional[List[Dict[str, Any]]]
    
    # LLM
    system_prompt: str
    user_prompt: str
    llm_response: str
    function_calls: Optional[List[Dict[str, Any]]]
    
    # Output
    final_answer: str
    metadata: Dict[str, Any]


def get_temperature_for_intent(intent: str) -> float:
    temperatures = {
        "cv_analysis": 0.2,
        "jd_analysis": 0.25,
        "jd_search": 0.3,
        "general": 0.4
    }
    return temperatures.get(intent, 0.3)


async def load_session_history_node(state: ChatState) -> ChatState:
    """Node 0: Load history & active positions from recruitment API"""
    session_id = state["session_id"]
    try:
        history = await recruitment_api.get_history(session_id, limit=settings.MAX_HISTORY_TURNS)
        state["conversation_history"] = history
    except Exception as e:
        print(f"[API Error] Could not load history: {e}")
        state["conversation_history"] = []
        
    try:
        active_positions = await recruitment_api.get_active_positions()
        state["active_position_ids"] = [p["id"] for p in active_positions]
    except Exception as e:
        print(f"[API Error] Could not load active positions: {e}")
        state["active_position_ids"] = []
        
    return state


def classify_intent_node(state: ChatState) -> ChatState:
    """Node 1: Classify user intent"""
    query = state["query"]
    result = intent_classifier.classify(query)
    
    print(f"[Intent] {result['intent']} (conf: {result['confidence']:.2f}, domain: {result['domain']})")
    
    state["intent"] = result["intent"]
    state["intent_confidence"] = result["confidence"]
    state["domain"] = result["domain"]
    
    return state


async def retrieve_context_node(state: ChatState) -> ChatState:
    """Node 2: Retrieve relevant context"""
    query = state["query"]
    intent = state["intent"]
    
    result = await retriever.retrieve_for_intent(
        query=query,
        intent=intent,
        candidate_id=state.get("candidate_id"),
        cv_id=state.get("cv_id"),
        jd_id=state.get("jd_id"),
        active_jd_ids=state.get("active_position_ids") if intent == "jd_search" else None
    )
    
    # Filter JDs by active positions if jd_search
    jd_context = result.get("jd_context", [])
    if intent == "jd_search" and state.get("active_position_ids"):
        active_ids = state["active_position_ids"]
        jd_context = [jd for jd in jd_context if jd.get("payload", {}).get("jdId") in active_ids]
        
    state["cv_context"] = result.get("cv_context", [])
    state["jd_context"] = jd_context
    state["retrieval_stats"] = result.get("retrieval_stats", {})
    
    return state


async def scoring_node(state: ChatState) -> ChatState:
    """Node 2.5: Batch scoring for jd_search intent"""
    intent = state["intent"]
    if intent != "jd_search" or not state["jd_context"] or not state["cv_context"]:
        state["scored_jobs"] = None
        return state
        
    print("[Scoring] Batch scoring JDs...")
    cv_text = state["cv_context"][0].get("payload", {}).get("chunkText", "") if state["cv_context"] else ""
    
    jds_text = ""
    for idx, jd in enumerate(state["jd_context"]):
        jd_id = jd.get("payload", {}).get("jdId", "unknown")
        jd_content = jd.get("payload", {}).get("jdText", "")
        jds_text += f"\n--- JD ID: {jd_id} ---\n{jd_content}\n"
        
    scoring_prompt = f"""
    Dưới đây là một CV và danh sách các JD (mô tả công việc).
    Hãy đánh giá mức độ phù hợp của CV với TỪNG JD theo thang điểm từ 0-100.
    
    ## CV:
    {cv_text}
    
    ## DANH SÁCH JDs:
    {jds_text}
    
    TRẢ VỀ DUY NHẤT một mảng JSON theo định dạng sau (không markdown, không giải thích):
    [
        {{
            "positionId": <JD ID>,
            "score": <điểm 0-100>,
            "feedback": "<nhận xét ngắn gọn>",
            "skillMatch": "<các kỹ năng phù hợp>",
            "skillMiss": "<các kỹ năng còn thiếu>"
        }}
    ]
    """
    
    llm = ChatGoogleGenerativeAI(
        model=settings.GEMINI_MODEL,
        temperature=0.1,
        max_output_tokens=settings.GEMINI_MAX_TOKENS,
        google_api_key=settings.GEMINI_API_KEY
    )
    
    response = await llm.ainvoke([HumanMessage(content=scoring_prompt)])
    try:
        content = response.content.strip()
        if content.startswith("```json"):
            content = content[7:-3]
        state["scored_jobs"] = json.loads(content)
        print(f"[Scoring] Scored {len(state['scored_jobs'])} JDs.")
    except Exception as e:
        print(f"[Scoring Error] Could not parse JSON array: {e}")
        state["scored_jobs"] = None
        
    return state


def build_prompts_node(state: ChatState) -> ChatState:
    """Node 3: Build prompts from context"""
    query = state["query"]
    intent = state["intent"]
    
    # If scored jobs exist, inject them into conversation or prompt
    if state.get("scored_jobs"):
        # Let's stringify the scores to inject into context
        scores_str = json.dumps(state["scored_jobs"], ensure_ascii=False, indent=2)
        system_prompt, user_prompt = get_prompt_for_intent(
            intent=intent,
            query=query,
            cv_context=state["cv_context"],
            jd_context=state.get("jd_context", []),
            conversation_history=state.get("conversation_history", [])
        )
        system_prompt += f"\n\nTHÔNG TIN ĐÁNH GIÁ (SCORE):\nDưới đây là kết quả đánh giá mức độ phù hợp CV - JDs:\n{scores_str}\n\nHãy tư vấn cho User dựa trên điểm số này. Cảnh báo user nếu score < 70, không để họ apply."
    else:
        system_prompt, user_prompt = get_prompt_for_intent(
            intent=intent,
            query=query,
            cv_context=state["cv_context"],
            jd_context=state.get("jd_context", []),
            conversation_history=state.get("conversation_history", [])
        )
        
    state["system_prompt"] = system_prompt
    state["user_prompt"] = user_prompt
    return state


async def llm_reasoning_node(state: ChatState) -> ChatState:
    """Node 4: LLM reasoning with context and tools"""
    system_prompt = state["system_prompt"]
    user_prompt = state["user_prompt"]
    intent = state["intent"]
    temperature = get_temperature_for_intent(intent)

    llm = ChatGoogleGenerativeAI(
        model=settings.GEMINI_MODEL,
        temperature=temperature,
        max_output_tokens=settings.GEMINI_MAX_TOKENS,
        google_api_key=settings.GEMINI_API_KEY
    ).bind_tools(CANDIDATE_TOOLS)

    messages = [
        SystemMessage(content=system_prompt),
        HumanMessage(content=user_prompt)
    ]

    response = await llm.ainvoke(messages)
    
    content = response.content
    state["llm_response"] = " ".join([b.get("text", "") for b in content if isinstance(b, dict) and "text" in b]) if isinstance(content, list) else str(content)
    state["function_calls"] = None
    
    if response.tool_calls:
        print("[LLM] Tool calls detected:", response.tool_calls)
        state["function_calls"] = []
        tool_map = {t.name: t for t in CANDIDATE_TOOLS}
        messages.append(response)  # AIMessage with tool_use blocks
        for call in response.tool_calls:
            tool_name = call['name']
            tool_args = call['args']
            
            if tool_name == 'finalize_application':
                try:
                    tool_res = await tool_map["finalize_application"].ainvoke({
                        **tool_args,
                        "candidate_id": state["candidate_id"],
                        "session_id": state["session_id"]
                    })
                    state["function_calls"].append({
                        "name": tool_name,
                        "arguments": tool_args,
                        "result": tool_res
                    })
                    messages.append(ToolMessage(content=str(tool_res), tool_call_id=call["id"]))
                except Exception as e:
                    messages.append(ToolMessage(content=f"Lỗi nộp đơn: {str(e)}", tool_call_id=call["id"]))
            elif tool_name == 'evaluate_cv_fit':
                # evaluate_cv_fit is a signal for the scoring node — scores already computed
                scored_summary = json.dumps(state.get("scored_jobs") or [], ensure_ascii=False)
                state["function_calls"].append({
                    "name": tool_name,
                    "arguments": tool_args
                })
                messages.append(ToolMessage(content=scored_summary, tool_call_id=call["id"]))
        

        # Second LLM pass to synthesize tool results into a natural response
        if state["function_calls"]:
            llm_no_tools = ChatGoogleGenerativeAI(
                model=settings.GEMINI_MODEL,
                temperature=temperature,
                max_output_tokens=settings.GEMINI_MAX_TOKENS,
                google_api_key=settings.GEMINI_API_KEY
            )
            second_response = await llm_no_tools.ainvoke(messages)
            content = second_response.content
            state["llm_response"] = " ".join([b.get("text", "") for b in content if isinstance(b, dict) and "text" in b]) if isinstance(content, list) else str(content)

    return state



async def save_turn_node(state: ChatState) -> ChatState:
    """Node 4.5: Save message to SQL via recruitment API"""
    try:
        # Save user message
        await recruitment_api.save_message(
            session_id=state["session_id"],
            role="USER",
            content=state["query"]
        )
        
        # Save assistant message
        function_call = state.get("function_calls")
        if state.get("scored_jobs") and not function_call:
            # We can also store the scored_jobs as a function_call JSON for caching if needed
            function_call = {"scored_jobs": state["scored_jobs"]}
            
        await recruitment_api.save_message(
            session_id=state["session_id"],
            role="ASSISTANT",
            content=state["llm_response"],
            function_call=function_call
        )
    except Exception as e:
        print(f"[API Error] Could not save turn: {e}")
        
    return state


def format_response_node(state: ChatState) -> ChatState:
    """Node 5: Format final response"""
    state["final_answer"] = state["llm_response"]
    
    state["metadata"] = {
        "intent": state["intent"],
        "intent_confidence": state["intent_confidence"],
        "domain": state["domain"],
        "cv_chunks_used": len(state.get("cv_context", [])),
        "jd_docs_used": len(state.get("jd_context", [])),
        "temperature_used": get_temperature_for_intent(state["intent"]),
        "function_calls": state.get("function_calls"),
        "scored_jobs": state.get("scored_jobs")
    }
    return state


def should_retrieve(state: ChatState) -> Literal["retrieve", "skip_retrieve"]:
    intent = state["intent"]
    domain = state.get("domain", "career")
    if intent in ["jd_search", "jd_analysis", "cv_analysis"]:
        return "retrieve"
    if intent == "general" and domain == "general" and state["intent_confidence"] > 0.7:
        return "skip_retrieve"
    return "retrieve"


def create_candidate_graph():
    workflow = StateGraph(ChatState)
    
    workflow.add_node("load_session_history", load_session_history_node)
    workflow.add_node("classify_intent", classify_intent_node)
    workflow.add_node("retrieve_context", retrieve_context_node)
    workflow.add_node("scoring", scoring_node)
    workflow.add_node("build_prompts", build_prompts_node)
    workflow.add_node("llm_reasoning", llm_reasoning_node)
    workflow.add_node("save_turn", save_turn_node)
    workflow.add_node("format_response", format_response_node)
    
    workflow.set_entry_point("load_session_history")
    workflow.add_edge("load_session_history", "classify_intent")
    
    workflow.add_conditional_edges(
        "classify_intent",
        should_retrieve,
        {
            "retrieve": "retrieve_context",
            "skip_retrieve": "build_prompts"
        }
    )
    
    workflow.add_edge("retrieve_context", "scoring")
    workflow.add_edge("scoring", "build_prompts")
    workflow.add_edge("build_prompts", "llm_reasoning")
    workflow.add_edge("llm_reasoning", "save_turn")
    workflow.add_edge("save_turn", "format_response")
    workflow.add_edge("format_response", END)
    
    return workflow.compile()


class CandidateChatbot:
    def __init__(self):
        self.graph = create_candidate_graph()
    
    async def chat(
        self,
        query: str,
        session_id: str,
        candidate_id: str,
        cv_id: Optional[int] = None
    ) -> Dict[str, Any]:
        initial_state = {
            "query": query,
            "session_id": session_id,
            "candidate_id": candidate_id,
            "cv_id": cv_id,
            "jd_id": None,
            "conversation_history": [],
            "active_position_ids": [],
            "cv_context": [],
            "jd_context": [],
            "retrieval_stats": {}
        }
        
        final_state = await self.graph.ainvoke(initial_state, {"recursion_limit": 50})
        
        return {
            "answer": final_state["final_answer"],
            "metadata": final_state["metadata"]
        }

candidate_chatbot = CandidateChatbot()
