"""
LangGraph workflow for the Candidate chatbot.
Pipeline: Intent Classification → Parallel Retrieval → Scoring → Prompt Build → LLM → Persist → Response

Performance optimizations:
- Dual-mode JD retrieval: full JD text only on Turn 1 (no scoring cache),
  section-based Qdrant chunks from Turn 2+ (scoring already cached).
- Hard-rule Tầng 1 routing: apply-intent detected before scoring node to short-circuit.
- Qdrant CV + JD queries run concurrently via asyncio.gather inside the retriever.
"""

import json
import re
import asyncio
from typing import TypedDict, Literal, Optional, List, Dict, Any
from langgraph.graph import StateGraph, END
from langchain_google_genai import ChatGoogleGenerativeAI
from langchain_core.messages import SystemMessage, HumanMessage, ToolMessage

from app.rag.intent import intent_classifier
from app.rag.prompts import get_prompt_for_intent, build_cv_context, build_jd_context
from app.services.retriever import retriever
from app.services.recruitment_api import recruitment_api
from app.rag.candidate_tools import CANDIDATE_TOOLS
from app.config import get_settings

settings = get_settings()

# ---------------------------------------------------------------------------
# Apply intent — hard-rule keywords (Tầng 1 routing, zero LLM cost)
# ---------------------------------------------------------------------------

_APPLY_PATTERNS = re.compile(
    r"\b(apply|nộp đơn|nop don|ứng tuyển|ung tuyen|finalize|submit.*application|i want to apply|giúp tôi apply|help me apply)\b",
    re.IGNORECASE,
)


def _is_apply_intent(query: str) -> bool:
    """Tầng 1: Hard-rule detect apply intent trước khi qua scoring node."""
    return bool(_APPLY_PATTERNS.search(query))


class ChatState(TypedDict):
    """State passed between graph nodes."""

    # Input
    session_id: str
    query: str
    candidate_id: str
    cv_id: Optional[int]
    jd_id: Optional[int]

    # Session
    conversation_history: List[Dict[str, Any]]
    active_position_ids: List[int]
    # Map of {positionId -> "Name Language Level"} built in Node 0.
    # Used by Node 4 for clean finalize confirmation without markdown artifacts.
    position_ref_map: Dict[int, str]

    # Processing
    intent: Literal["jd_search", "jd_analysis", "cv_analysis", "general"]
    intent_confidence: float
    domain: str
    is_apply_intent: bool  # Tầng 1 hard-rule flag

    # Retrieved context
    cv_context: List[Dict[str, Any]]
    jd_context: List[Dict[str, Any]]
    retrieval_stats: Dict[str, Any]

    # Pre-screening scores (lightweight pass before main LLM call)
    scored_jobs: Optional[List[Dict[str, Any]]]

    # LLM pipeline
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
        "general": 0.4,
    }
    return temperatures.get(intent, 0.3)


def _extract_llm_text(content: Any) -> str:
    """Normalise LLM response content regardless of whether it is a str or a list of blocks."""
    if isinstance(content, list):
        return " ".join(b.get("text", "") for b in content if isinstance(b, dict) and "text" in b)
    return str(content)


# ---------------------------------------------------------------------------
# Node 0 — Load session history + active positions (concurrent)
# ---------------------------------------------------------------------------

async def load_session_history_node(state: ChatState) -> ChatState:
    """Fetch conversation history and active positions concurrently.
    Builds `position_ref_map` for downstream O(1) ID-to-name lookups.
    Also restores scoring cache and detects apply intent (Tầng 1) early.
    """

    async def _get_history():
        try:
            history = await recruitment_api.get_history(state["session_id"], limit=settings.MAX_HISTORY_TURNS)

            # --- Scoring Cache Extraction ---
            # Look backwards for the most recent ASSISTANT message containing scored_jobs array
            for turn in reversed(history):
                if turn.get("role") == "ASSISTANT":
                    func_data_str = turn.get("functionCall")
                    if func_data_str:
                        try:
                            func_data = json.loads(func_data_str)
                            if isinstance(func_data, dict) and "scored_jobs" in func_data:
                                state["scored_jobs"] = func_data["scored_jobs"]
                                print(f"[Cache Hitting] Restored {len(state['scored_jobs'])} scored jobs from history.")
                                break
                        except json.JSONDecodeError:
                            continue

            return history
        except Exception as e:
            print(f"[API Error] Could not load history: {e}")
            return []

    async def _get_active_positions():
        try:
            positions = await recruitment_api.get_active_positions()
            return positions
        except Exception as e:
            print(f"[API Error] Could not load active positions: {e}")
            return []

    history, positions = await asyncio.gather(_get_history(), _get_active_positions())
    state["conversation_history"] = history
    state["active_position_ids"] = [p["id"] for p in positions]

    # Build reference map: {id -> "Name Language Level"} for clean UX in finalize step
    ref_map: Dict[int, str] = {}
    for p in positions:
        parts = [p.get("name", ""), p.get("language", ""), p.get("level", "")]
        label = " ".join(part for part in parts if part)
        ref_map[p["id"]] = label
    state["position_ref_map"] = ref_map

    # Tầng 1: Detect apply intent early — will short-circuit scoring node if cached
    state["is_apply_intent"] = _is_apply_intent(state["query"])
    if state["is_apply_intent"]:
        print("[Tầng 1] Apply intent detected via hard-rule — will skip scoring if cache hit.")

    return state


# ---------------------------------------------------------------------------
# Node 1 — Intent classification
# ---------------------------------------------------------------------------

def classify_intent_node(state: ChatState) -> ChatState:
    """Classify the candidate's query intent.
    Apply intent detected in Tầng 1 overrides to jd_search so scoring context is available.
    """
    result = intent_classifier.classify(state["query"])
    print(f"[Intent] {result['intent']} (conf: {result['confidence']:.2f}, domain: {result['domain']})")
    state["intent"] = result["intent"]
    state["intent_confidence"] = result["confidence"]
    state["domain"] = result["domain"]

    # Apply intent always needs jd_search context (scored_jobs) to finalize
    if state.get("is_apply_intent") and state["intent"] not in ("jd_search",):
        state["intent"] = "jd_search"
        print("[Tầng 1] Intent overridden to jd_search for apply flow.")

    return state


# ---------------------------------------------------------------------------
# Node 2 — Retrieve context (Dual-mode JD retrieval)
# ---------------------------------------------------------------------------

async def retrieve_context_node(state: ChatState) -> ChatState:
    """
    Node 2 — Dual-mode JD retrieval strategy based on scoring cache:

    Mode A — No cache (Turn 1):
      1. Qdrant returns lightweight JD chunk hits.
      2. Extract unique positionIds.
      3. Fetch FULL JdText per positionId via Java API (Small-to-Big).
      4. Feed full text to scoring LLM for precise evaluation.

    Mode B — Cache exists (Turn 2+):
      1. Qdrant returns section-based chunk hits (semantic search).
      2. Use chunk hits directly — no Java API call.
      3. LLM answers from relevant section chunks (benefits, process, etc.).
      Benefit: Reduces latency + improves answer precision for follow-up questions.
    """
    intent = state["intent"]
    has_scoring_cache = bool(state.get("scored_jobs"))

    result = await retriever.retrieve_for_intent(
        query=state["query"],
        intent=intent,
        candidate_id=state.get("candidate_id"),
        cv_id=state.get("cv_id"),
        jd_id=state.get("jd_id"),
        active_jd_ids=state.get("active_position_ids") if intent == "jd_search" else None,
    )

    cv_context = result.get("cv_context", [])
    chunk_hits  = result.get("jd_context", [])
    jd_context  = chunk_hits  # default: use raw chunks

    if intent in ("jd_search", "jd_analysis") and chunk_hits:
        # Collect unique positionIds from Qdrant chunk payloads
        seen: set = set()
        position_ids: List[int] = []
        for hit in chunk_hits:
            pid = hit.get("payload", {}).get("positionId")
            if pid is not None and pid not in seen:
                seen.add(pid)
                position_ids.append(pid)

        if not has_scoring_cache and position_ids:
            # Mode A: First call — fetch full JD text for scoring precision
            print(f"[Retriever] Mode A (no cache): {len(chunk_hits)} chunks → fetching full JD for {len(position_ids)} positions")
            try:
                full_jd_list = await recruitment_api.get_position_details(position_ids)
                jd_context = [
                    {
                        "score": 1.0,
                        "payload": {
                            "positionId":   jd["id"],
                            "positionName": jd.get("name", ""),
                            "language":     jd.get("language", ""),
                            "level":        jd.get("level", ""),
                            "jdText":       jd.get("jdText", ""),
                        },
                    }
                    for jd in full_jd_list
                    if jd.get("jdText")
                ]
                print(f"[Retriever] Mode A: Expanded to {len(jd_context)} full-JD objects")
            except Exception as e:
                print(f"[Retriever] Mode A: Full JD fetch failed, falling back to chunks: {e}")
        else:
            # Mode B: Subsequent calls — use Qdrant section chunks directly
            jd_context = chunk_hits
            reason = "scoring cache hit" if has_scoring_cache else "no position IDs"
            print(f"[Retriever] Mode B ({reason}): using {len(jd_context)} section chunks")

        # Python-side active-position guard (belt-and-suspenders)
        if intent == "jd_search" and state.get("active_position_ids"):
            active_ids = set(state["active_position_ids"])
            jd_context = [
                jd for jd in jd_context
                if jd.get("payload", {}).get("positionId") in active_ids
            ]

    state["cv_context"]      = cv_context
    state["jd_context"]      = jd_context
    state["retrieval_stats"] = result.get("retrieval_stats", {})
    return state


# ---------------------------------------------------------------------------
# Node 2.5 — Deep CV-JD scoring (jd_search, Turn 1 only)
# ---------------------------------------------------------------------------

async def scoring_node(state: ChatState) -> ChatState:
    """
    Node 2.5 — Deep CV-JD scoring using the dedicated Pro model (Tiered LLM Routing).
    Runs only for jd_search intent on Turn 1. Skipped when:
      - Intent is not jd_search
      - No JD or CV context available
      - scored_jobs cache already populated (Turn 2+)
      - Apply intent with existing cache (Tầng 1 short-circuit)

    Uses unified 3-category scoring formula aligned with LlmAnalysisService.java.
    """
    if state["intent"] != "jd_search" or not state["jd_context"] or not state["cv_context"]:
        state["scored_jobs"] = state.get("scored_jobs")
        return state

    # --- Cache Check (also covers Tầng 1 apply short-circuit) ---
    if state.get("scored_jobs"):
        print("[Scoring] Cache hit — bypassing LLM scoring call.")
        return state

    print(f"[Scoring] Running deep scoring with model: {settings.SCORING_GEMINI_MODEL}...")

    cv_profile = build_cv_context(state["cv_context"])

    jds_block = ""
    for jd in state["jd_context"]:
        payload  = jd.get("payload", {})
        jd_id    = payload.get("positionId", "unknown")
        jd_title = " ".join(filter(None, [
            payload.get("positionName"),
            payload.get("language"),
            payload.get("level"),
        ])) or payload.get("positionName", "Unknown Position")
        jd_text  = payload.get("jdText", "")
        jds_block += f"\n[JD ID: {jd_id} | Title: {jd_title}]\n{jd_text}\n"

    # Unified 3-category formula — aligned with LlmAnalysisService.java
    # Using reduced strictness: -8 per missing skill (down from -10), relaxed Depth thresholds
    scoring_prompt = f"""You are a strict HR scoring system. Score each CV against the provided JDs.

SCORING SYSTEM (Total 100 pts):

1. Core Requirement Fit (Max 60 pts):
   - Start: 60 pts.
   - Deduct 8 pts for each missing REQUIRED skill explicitly stated in JD.
   - Deduct 12 pts if candidate has < 70%% of required tech stack overall.

2. Depth of Experience (Max 30 pts):
   - 22-30 pts: Led architectural decisions, measurable business impact, system design ownership.
   - 12-21 pts: Mid-level, implemented features with rationale, understands trade-offs.
   - 0-11 pts:  Junior/basic CRUD projects only, no evidence of scale or architectural thinking.

3. Professionalism & Gaps (Max 10 pts):
   - Deduct 4 pts for missing required degree/certification if explicitly required.
   - Deduct 3 pts for poor CV structure or inconsistent skill claims.

RULES:
- STRICT: If a skill is not explicitly stated in CV, assume candidate does NOT have it.
- NO HALLUCINATION: Do not infer skills from project context alone.
- HIERARCHICAL SKILL INFERENCE (Overqualified Candidates): Level Hierarchy is (Intern < Fresher < Junior < Mid-level < Senior < Principal). If a candidate demonstrates advanced skills (e.g., Microservices, React, System Design) or has a higher experience level but the JD requires lower-level basic skills (e.g., HTML/CSS, basic Java), DO NOT penalize for missing basic keywords. Assume basic proficiency. Score them highly. In the feedback, explicitly label them as "Overqualified" and state that their level exceeds the position's requirements.
- Score 0 only if candidate clearly fails ALL minimum requirements.
- Use the same logic consistently across all JDs.

CV:
{cv_profile}

JDs to score:
{jds_block}

Return EXACTLY this JSON array (no markdown, no preamble):
[
  {{
    "positionId": <integer>,
    "matchedCount": <number of skills matched>,
    "missedCount": <number of required skills missing>,
    "score": <final score 0-100>,
    "feedback": "<1 concise HR-tone sentence>",
    "skillMatch": ["<skill>", "<skill>"],
    "skillMiss": ["<skill>", "<skill>"]
  }}
]"""

    llm = ChatGoogleGenerativeAI(
        model=settings.SCORING_GEMINI_MODEL,
        temperature=0.0,
        max_output_tokens=1024,
        google_api_key=settings.GEMINI_API_KEY,
    )

    try:
        response = await llm.ainvoke([HumanMessage(content=scoring_prompt)])
        raw = response.content.strip()
        if raw.startswith("```"):
            raw = raw.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
        state["scored_jobs"] = json.loads(raw)
        print(f"[Scoring] Scored {len(state['scored_jobs'])} positions.")
    except Exception as e:
        print(f"[Scoring Error] JSON parse failed: {e} — proceeding without scores.")
        state["scored_jobs"] = None

    return state


# ---------------------------------------------------------------------------
# Node 3 — Build prompts
# ---------------------------------------------------------------------------

def build_prompts_node(state: ChatState) -> ChatState:
    """Assemble the system + user prompts, including pre-screened scores for jd_search."""
    system_prompt, user_prompt = get_prompt_for_intent(
        intent=state["intent"],
        query=state["query"],
        cv_context=state.get("cv_context", []),
        jd_context=state.get("jd_context", []),
        conversation_history=state.get("conversation_history", []),
        scored_jobs=state.get("scored_jobs"),
    )
    state["system_prompt"] = system_prompt
    state["user_prompt"] = user_prompt
    return state


# ---------------------------------------------------------------------------
# Node 4 — LLM reasoning with tool support
# ---------------------------------------------------------------------------

async def llm_reasoning_node(state: ChatState) -> ChatState:
    """Single LLM call that generates the full candidate-facing response."""
    intent = state["intent"]
    temperature = get_temperature_for_intent(intent)

    llm = ChatGoogleGenerativeAI(
        model=settings.GEMINI_MODEL,
        temperature=temperature,
        max_output_tokens=settings.GEMINI_MAX_TOKENS,
        google_api_key=settings.GEMINI_API_KEY,
    ).bind_tools(CANDIDATE_TOOLS)

    messages = [
        SystemMessage(content=state["system_prompt"]),
        HumanMessage(content=state["user_prompt"]),
    ]

    response = await llm.ainvoke(messages)
    state["llm_response"] = _extract_llm_text(response.content)
    state["function_calls"] = None

    if not response.tool_calls:
        return state

    # --- Tool execution ---
    print("[LLM] Tool calls detected:", response.tool_calls)
    state["function_calls"] = []
    tool_map = {t.name: t for t in CANDIDATE_TOOLS}
    messages.append(response)

    did_finalize_app = False

    for call in response.tool_calls:
        tool_name = call["name"]
        tool_args = call["args"]

        if tool_name == "finalize_application":
            did_finalize_app = True
            pos_id = tool_args.get("position_id")

            # Resolve position label from ref_map (built in Node 0)
            ref_map = state.get("position_ref_map", {})
            applied_position_name = ref_map.get(pos_id, f"position #{pos_id}")

            # Resolve position_id from scored_jobs cache if LLM passed wrong/None value
            if not pos_id and state.get("scored_jobs"):
                best = max(state["scored_jobs"], key=lambda j: j.get("score", 0))
                pos_id = best.get("positionId")
                tool_args = {**tool_args, "position_id": pos_id}
                applied_position_name = ref_map.get(pos_id, f"position #{pos_id}")
                print(f"[Tầng 1] Resolved position_id={pos_id} from scored_jobs cache.")

            try:
                tool_res = await tool_map["finalize_application"].ainvoke({
                    **tool_args,
                    "candidate_id": state["candidate_id"],
                    "session_id":   state["session_id"],
                })
                state["function_calls"].append({"name": tool_name, "arguments": tool_args, "result": tool_res})
                messages.append(ToolMessage(content=str(tool_res), tool_call_id=call["id"]))
            except Exception as e:
                messages.append(ToolMessage(content=f"Application error: {str(e)}", tool_call_id=call["id"]))

        elif tool_name == "evaluate_cv_fit":
            scored_summary = json.dumps(state.get("scored_jobs") or [], ensure_ascii=False)
            state["function_calls"].append({"name": tool_name, "arguments": tool_args})
            messages.append(ToolMessage(content=scored_summary, tool_call_id=call["id"]))

        elif tool_name == "check_application_status":
            # Auto-inject candidate_id — user never needs to provide their own UUID
            enriched_args = {
                **tool_args,
                "candidate_id": state["candidate_id"],
            }
            try:
                tool_res = await tool_map["check_application_status"].ainvoke(enriched_args)
                state["function_calls"].append({"name": tool_name, "arguments": enriched_args, "result": tool_res})
                messages.append(ToolMessage(content=str(tool_res), tool_call_id=call["id"]))
            except Exception as e:
                messages.append(ToolMessage(content=f"Status check error: {str(e)}", tool_call_id=call["id"]))

    # Short-circuit: skip second LLM pass if finalize_application was successful
    if did_finalize_app:
        state["llm_response"] = (
            f"I have successfully submitted your application for the **{applied_position_name}** position. "
            "Please wait for our HR response!"
        )
        return state

    # Second pass to synthesize tool results into natural language
    if state["function_calls"]:
        llm_no_tools = ChatGoogleGenerativeAI(
            model=settings.GEMINI_MODEL,
            temperature=temperature,
            max_output_tokens=settings.GEMINI_MAX_TOKENS,
            google_api_key=settings.GEMINI_API_KEY,
        )
        second_response = await llm_no_tools.ainvoke(messages)
        state["llm_response"] = _extract_llm_text(second_response.content)

    return state


# ---------------------------------------------------------------------------
# Node 4.5 — Persist turn to recruitment-service
# ---------------------------------------------------------------------------

async def save_turn_node(state: ChatState) -> ChatState:
    """Save user + assistant messages to recruitment-service (MySQL) via internal API."""
    try:
        await recruitment_api.save_message(
            session_id=state["session_id"],
            role="USER",
            content=state["query"],
        )

        function_call_payload: Optional[str] = None
        raw_calls = state.get("function_calls")
        if raw_calls:
            function_call_payload = json.dumps(raw_calls, ensure_ascii=False)
        elif state.get("scored_jobs"):
            function_call_payload = json.dumps(
                {"scored_jobs": state["scored_jobs"]}, ensure_ascii=False
            )

        await recruitment_api.save_message(
            session_id=state["session_id"],
            role="ASSISTANT",
            content=state["llm_response"],
            function_call=function_call_payload,
        )
    except Exception as e:
        print(f"[API Error] Could not save turn: {e}")

    return state


# ---------------------------------------------------------------------------
# Node 5 — Format final response
# ---------------------------------------------------------------------------

def format_response_node(state: ChatState) -> ChatState:
    """Package the final answer and metadata for the API layer."""
    state["final_answer"] = state["llm_response"]
    state["metadata"] = {
        "intent":             state["intent"],
        "intent_confidence":  state["intent_confidence"],
        "domain":             state["domain"],
        "is_apply_intent":    state.get("is_apply_intent", False),
        "cv_chunks_used":     len(state.get("cv_context", [])),
        "jd_docs_used":       len(state.get("jd_context", [])),
        "temperature_used":   get_temperature_for_intent(state["intent"]),
        "function_calls":     state.get("function_calls"),
        "scored_jobs":        state.get("scored_jobs"),
    }
    return state


# ---------------------------------------------------------------------------
# Routing decision
# ---------------------------------------------------------------------------

def should_retrieve(state: ChatState) -> Literal["retrieve", "skip_retrieve"]:
    intent = state["intent"]
    if intent in ("jd_search", "jd_analysis", "cv_analysis"):
        return "retrieve"
    if intent == "general" and state.get("domain") == "general" and state["intent_confidence"] > 0.7:
        return "skip_retrieve"
    return "retrieve"


# ---------------------------------------------------------------------------
# Graph construction
# ---------------------------------------------------------------------------

def create_candidate_graph():
    workflow = StateGraph(ChatState)

    workflow.add_node("load_session_history", load_session_history_node)
    workflow.add_node("classify_intent",      classify_intent_node)
    workflow.add_node("retrieve_context",     retrieve_context_node)
    workflow.add_node("scoring",              scoring_node)
    workflow.add_node("build_prompts",        build_prompts_node)
    workflow.add_node("llm_reasoning",        llm_reasoning_node)
    workflow.add_node("save_turn",            save_turn_node)
    workflow.add_node("format_response",      format_response_node)

    workflow.set_entry_point("load_session_history")
    workflow.add_edge("load_session_history", "classify_intent")

    workflow.add_conditional_edges(
        "classify_intent",
        should_retrieve,
        {"retrieve": "retrieve_context", "skip_retrieve": "build_prompts"},
    )

    workflow.add_edge("retrieve_context", "scoring")
    workflow.add_edge("scoring",          "build_prompts")
    workflow.add_edge("build_prompts",    "llm_reasoning")
    workflow.add_edge("llm_reasoning",    "save_turn")
    workflow.add_edge("save_turn",        "format_response")
    workflow.add_edge("format_response",  END)

    return workflow.compile()


# ---------------------------------------------------------------------------
# Public interface
# ---------------------------------------------------------------------------

class CandidateChatbot:
    def __init__(self):
        self.graph = create_candidate_graph()

    async def chat(
        self,
        query: str,
        session_id: str,
        candidate_id: str,
        cv_id: Optional[int] = None,
    ) -> Dict[str, Any]:
        initial_state: ChatState = {
            "query":                query,
            "session_id":           session_id,
            "candidate_id":         candidate_id,
            "cv_id":                cv_id,
            "jd_id":                None,
            "conversation_history": [],
            "active_position_ids":  [],
            "position_ref_map":     {},
            "cv_context":           [],
            "jd_context":           [],
            "retrieval_stats":      {},
            "scored_jobs":          None,
            "is_apply_intent":      False,
            "system_prompt":        "",
            "user_prompt":          "",
            "llm_response":         "",
            "function_calls":       None,
            "final_answer":         "",
            "metadata":             {},
        }

        final_state = await self.graph.ainvoke(initial_state, {"recursion_limit": 50})

        return {
            "answer":   final_state["final_answer"],
            "metadata": final_state["metadata"],
        }


candidate_chatbot = CandidateChatbot()
