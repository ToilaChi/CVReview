"""
LangGraph workflow for the HR chatbot.
Supports two modes driven by HRChatState.mode:
  - HR_MODE:        Qdrant filter on positionId + sourceType=HR
  - CANDIDATE_MODE: Resolve candidateIds from SQL → Qdrant filter on candidateId

Phase 0+1 changes:
  - _format_cv_context(): deduplicate chunks by candidateId (fixes CV count hallucination).
  - llm_hr_reasoning_node(): auto-inject position_id + resolve candidateId from sql_metadata
    into tool args before invoke (no more sensitive data requirement from HR).
  - Tầng 1 hard-rule: detect CV count queries → route to get_cv_summary tool.
  - Adaptive system prompt (Tầng 3): guides LLM to cite sources and handle sub-intents.
"""

import json
import re
from typing import TypedDict, Literal, Optional, List, Dict, Any
from langgraph.graph import StateGraph, END
from langchain_google_genai import ChatGoogleGenerativeAI
from langchain_core.messages import SystemMessage, HumanMessage, ToolMessage

from app.services.retriever import retriever
from app.services.recruitment_api import recruitment_api
from app.rag.hr_tools import HR_TOOLS
from app.config import get_settings


def _extract_llm_text(content) -> str:
    """Normalise LLM response content regardless of str vs list-of-blocks format."""
    if isinstance(content, list):
        return " ".join(b.get("text", "") for b in content if isinstance(b, dict) and "text" in b)
    return str(content)


settings = get_settings()

# ---------------------------------------------------------------------------
# Tầng 1: Hard-rule patterns for HR (zero LLM cost)
# ---------------------------------------------------------------------------

_CV_COUNT_PATTERN = re.compile(
    r"\b(bao nhiêu cv|how many cv|bao nhieu cv|số lượng cv|so luong cv|"
    r"upload bao nhiêu|da upload|tổng số cv|tong so cv|"
    r"how many resume|total cv|cv count|cv statistics|cv stat)\b",
    re.IGNORECASE,
)


def _is_cv_count_query(query: str) -> bool:
    """Tầng 1: Detect CV/resume count queries to route to get_cv_summary tool."""
    return bool(_CV_COUNT_PATTERN.search(query))


# ---------------------------------------------------------------------------
# State
# ---------------------------------------------------------------------------

class HRChatState(TypedDict):
    """State passed between every node in the HR graph."""

    # Inputs — set once at entry point
    query: str
    hr_id: str
    session_id: str
    position_id: int
    mode: Literal["HR_MODE", "CANDIDATE_MODE"]

    # Tầng 1 hard-rule flags
    is_cv_count_query: bool

    # Resolved at runtime
    candidate_ids: Optional[List[str]]       # CANDIDATE_MODE only
    sql_metadata: List[Dict[str, Any]]        # score/feedback rows from SQL

    # Session memory
    conversation_history: List[Dict[str, Any]]

    # RAG context
    cv_context: List[Dict[str, Any]]
    retrieval_stats: Dict[str, Any]

    # LLM pipeline
    system_prompt: str
    user_prompt: str
    llm_response: str
    function_calls: Optional[List[Dict[str, Any]]]

    # Output
    final_answer: str
    metadata: Dict[str, Any]


# ---------------------------------------------------------------------------
# Helper: build a single LLM instance
# ---------------------------------------------------------------------------

def _build_llm(temperature: float = 0.3) -> ChatGoogleGenerativeAI:
    return ChatGoogleGenerativeAI(
        model=settings.GEMINI_MODEL,
        temperature=temperature,
        max_output_tokens=settings.GEMINI_MAX_TOKENS,
        google_api_key=settings.GEMINI_API_KEY
    )


# ---------------------------------------------------------------------------
# Node 1 — Load session history
# ---------------------------------------------------------------------------

async def load_hr_session_history_node(state: HRChatState) -> HRChatState:
    """Fetch the sliding window of the last N turns and detect Tầng 1 hard-rule intents."""
    try:
        history = await recruitment_api.get_history(
            session_id=state["session_id"],
            limit=settings.MAX_HISTORY_TURNS
        )
        state["conversation_history"] = history
    except Exception as e:
        print(f"[HR Graph] Could not load history: {e}")
        state["conversation_history"] = []

    # Tầng 1: Detect CV count query before retrieval
    state["is_cv_count_query"] = _is_cv_count_query(state["query"])
    if state["is_cv_count_query"]:
        print("[Tầng 1] CV count query detected — will route to get_cv_summary tool.")

    return state


# ---------------------------------------------------------------------------
# Node 2 — Load candidate scope (CANDIDATE_MODE only)
# ---------------------------------------------------------------------------

async def load_candidate_scope_node(state: HRChatState) -> HRChatState:
    """
    CANDIDATE_MODE: call SQL to get all candidateIds who applied for this position.
    The result is used to build the Qdrant MatchAny filter on master CVs.
    HR_MODE: sets candidate_ids to None so the HR-mode retrieval path is taken.
    """
    if state["mode"] != "CANDIDATE_MODE":
        state["candidate_ids"] = None
        state["sql_metadata"] = []
        return state

    try:
        applications = await recruitment_api.get_applications(
            position_id=state["position_id"]
        )
        state["candidate_ids"] = [app["candidateId"] for app in applications]
        state["sql_metadata"] = applications
    except Exception as e:
        print(f"[HR Graph] Could not load candidate applications: {e}")
        state["candidate_ids"] = []
        state["sql_metadata"] = []

    return state


# ---------------------------------------------------------------------------
# Node 3 — Retrieve HR context from Qdrant
# ---------------------------------------------------------------------------

async def retrieve_hr_context_node(state: HRChatState) -> HRChatState:
    """
    Branch on mode:
      HR_MODE       → filter positionId + sourceType=HR
      CANDIDATE_MODE → filter candidateId IN [list] + sourceType=CANDIDATE

    Skip Qdrant retrieval for Tầng 1 CV count queries — data comes from SQL endpoint.
    """
    # Tầng 1: CV count queries don't need vector search
    if state.get("is_cv_count_query"):
        state["cv_context"] = []
        state["retrieval_stats"] = {"note": "skipped_for_cv_count_query"}
        return state

    query = state["query"]

    if state["mode"] == "HR_MODE":
        result = await retriever.retrieve_for_hr_mode_hr(
            query=query,
            position_id=state["position_id"]
        )
    else:
        candidate_ids = state.get("candidate_ids") or []
        if candidate_ids:
            result = await retriever.retrieve_for_hr_mode_candidate(
                query=query,
                candidate_ids=candidate_ids
            )
        else:
            result = {"cv_context": [], "jd_context": [], "retrieval_stats": {"note": "no_applications"}}

    state["cv_context"] = result.get("cv_context", [])
    state["retrieval_stats"] = result.get("retrieval_stats", {})

    return state


# ---------------------------------------------------------------------------
# Node 4 — Build prompts
# ---------------------------------------------------------------------------

def _format_history(history: List[Dict[str, Any]]) -> str:
    """Convert DB history rows into a compact conversation transcript."""
    if not history:
        return ""
    lines = []
    for msg in history:
        role_label = "HR" if msg.get("role") == "USER" else "Assistant"
        lines.append(f"{role_label}: {msg.get('content', '')}")
    return "\n".join(lines)


def _format_cv_context(cv_context: List[Dict[str, Any]]) -> str:
    """
    Format retrieved CV chunks into a readable block for the HR prompt.
    Deduplicates chunks by candidateId to prevent chunk-count vs candidate-count confusion.
    Each unique candidate is presented as one block regardless of how many chunks were retrieved.
    """
    if not cv_context:
        return "No CV data found for the current filter criteria."

    # Group chunks by candidateId
    candidate_chunks: Dict[str, List[str]] = {}
    candidate_scores: Dict[str, float] = {}

    for chunk in cv_context:
        payload     = chunk.get("payload", {})
        cid         = payload.get("candidateId", "unknown")
        chunk_text  = payload.get("chunkText", "").strip()
        score       = chunk.get("score", 0.0)

        if cid not in candidate_chunks:
            candidate_chunks[cid] = []
            candidate_scores[cid] = score  # Keep highest similarity score

        if chunk_text:
            candidate_chunks[cid].append(chunk_text)
        if score > candidate_scores[cid]:
            candidate_scores[cid] = score

    # Format one block per unique candidate
    parts = []
    for i, (cid, texts) in enumerate(candidate_chunks.items(), 1):
        best_score  = candidate_scores[cid]
        combined    = "\n".join(texts)
        parts.append(
            f"--- Candidate #{i} | ID: {cid} | Best Similarity: {best_score:.2f} ---\n{combined}"
        )

    total_candidates = len(candidate_chunks)
    header = f"[{total_candidates} unique candidate(s) found]\n\n"
    return header + "\n\n".join(parts)


def _format_sql_metadata(sql_metadata: List[Dict[str, Any]]) -> str:
    """Format application score rows from SQL into a compact summary."""
    if not sql_metadata:
        return ""
    rows = []
    for app in sql_metadata:
        rows.append(
            f"• CandidateId: {app.get('candidateId')} "
            f"| Name: {app.get('candidateName', 'N/A')} "
            f"| Score: {app.get('score', 'N/A')} "
            f"| Feedback: {app.get('feedback', 'N/A')}"
        )
    return "\n".join(rows)


def _resolve_candidate_from_metadata(
    candidate_name: str,
    sql_metadata: List[Dict[str, Any]]
) -> Optional[Dict[str, Any]]:
    """
    Fuzzy-resolve a candidate record from sql_metadata by name.
    Used to auto-inject candidateId when HR provides only a name in their request.
    Returns the first matching record or None.
    """
    if not candidate_name or not sql_metadata:
        return None

    name_lower = candidate_name.lower().strip()
    for app in sql_metadata:
        stored_name = (app.get("candidateName") or "").lower().strip()
        if stored_name and (name_lower in stored_name or stored_name in name_lower):
            return app

    return None


# Adaptive system prompt — Tầng 3 (zero extra API cost)
_ADAPTIVE_INSTRUCTION = """
Before responding, silently determine what the HR actually needs:
- COUNT/STATS question → answer with exact numbers from provided data ONLY; never estimate
- SALARY/BENEFITS question → extract only from JD text; never invent numbers
- EMAIL/ACTION request → invoke the appropriate tool immediately
- CANDIDATE COMPARISON → reference specific CV data for each candidate
- If the required data is NOT in the provided context → state explicitly what is missing

Every factual claim MUST trace to a CV chunk, SQL record, or JD section shown above.
Never fabricate candidate details, scores, or contact information.
"""


def build_hr_prompts_node(state: HRChatState) -> HRChatState:
    """Assemble system + user prompts for the HR LLM call."""
    mode_label  = "HR Mode (sourced CVs)" if state["mode"] == "HR_MODE" else "Candidate Mode (inbound applications)"
    history_text = _format_history(state.get("conversation_history", []))
    cv_text      = _format_cv_context(state.get("cv_context", []))
    sql_text     = _format_sql_metadata(state.get("sql_metadata", []))

    system_prompt = f"""You are a Senior HR Talent Acquisition Specialist assisting with recruitment decisions.
Current mode: {mode_label} | Position ID: {state['position_id']}

Guidelines:
- Respond concisely and professionally — as an experienced recruiter, not a chatbot.
- Base all assessments strictly on the CV data and scores provided — never fabricate candidate details.
- When HR requests to send an email, invoke the `send_interview_email` tool.
- When HR requests detailed candidate information, invoke the `get_candidate_details` tool.
- When HR asks about CV count or statistics, invoke the `get_cv_summary` tool.
- Responses must be in English.
{_ADAPTIVE_INSTRUCTION}"""

    user_prompt = f"""## Conversation History:
{history_text if history_text else "(New session)"}

## CV Data Retrieved from System:
{cv_text}
"""
    if sql_text:
        user_prompt += f"\n## Application Scores & Feedback from Database:\n{sql_text}\n"

    user_prompt += f"\n## HR Question:\n{state['query']}"

    state["system_prompt"] = system_prompt
    state["user_prompt"]   = user_prompt
    return state


# ---------------------------------------------------------------------------
# Node 5 — LLM reasoning with tool loop + auto-inject state into tool args
# ---------------------------------------------------------------------------

async def llm_hr_reasoning_node(state: HRChatState) -> HRChatState:
    """
    Gemini call with HR_TOOLS bound.
    Auto-injects system data (position_id, candidateId) into tool args before invoke.
    This eliminates the need for HR to provide sensitive IDs manually.

    Tool arg enrichment strategy:
      send_interview_email → inject position_id from state; resolve candidateId from sql_metadata by name
      get_candidate_details → inject position_id from state
      get_cv_summary → inject position_id from state
    """
    llm = _build_llm(temperature=0.3).bind_tools(HR_TOOLS)
    messages = [
        SystemMessage(content=state["system_prompt"]),
        HumanMessage(content=state["user_prompt"])
    ]

    response = await llm.ainvoke(messages)
    state["function_calls"] = None

    if not response.tool_calls:
        state["llm_response"] = _extract_llm_text(response.content)
        return state

    # --- Tool execution loop with auto-injection ---
    executed_calls = []
    tool_map = {t.name: t for t in HR_TOOLS}
    messages.append(response)

    for call in response.tool_calls:
        tool_name = call["name"]
        tool_args = call["args"].copy()  # Copy to avoid mutating LLM output

        # --- Auto-inject position_id (always available from HRChatState) ---
        if tool_name in ("send_interview_email", "get_candidate_details", "get_cv_summary"):
            if not tool_args.get("position_id"):
                tool_args["position_id"] = state["position_id"]
                print(f"[Tool Inject] Injected position_id={state['position_id']} into {tool_name}")

        # --- Auto-resolve candidateId from sql_metadata by candidate name ---
        if tool_name == "send_interview_email":
            if not tool_args.get("candidate_id"):
                candidate_name = tool_args.get("candidate_name", "")
                resolved = _resolve_candidate_from_metadata(candidate_name, state.get("sql_metadata", []))
                if resolved:
                    tool_args["candidate_id"]    = resolved.get("candidateId", "")
                    tool_args["candidate_email"]  = tool_args.get("candidate_email") or resolved.get("candidateEmail", "")
                    print(f"[Tool Inject] Resolved candidateId={tool_args['candidate_id']} for '{candidate_name}'")
                else:
                    print(f"[Tool Inject] Could not resolve candidateId for '{candidate_name}' — HR must provide.")

        tool_instance = tool_map.get(tool_name)
        if tool_instance is None:
            tool_result = f"Tool '{tool_name}' does not exist."
        else:
            try:
                tool_result = await tool_instance.ainvoke(tool_args)
            except Exception as e:
                tool_result = f"Error executing tool {tool_name}: {str(e)}"

        executed_calls.append({"name": tool_name, "arguments": tool_args, "result": tool_result})
        messages.append(ToolMessage(content=str(tool_result), tool_call_id=call["id"]))

    state["function_calls"] = executed_calls

    # Second LLM call to synthesize tool results into natural language
    llm_no_tools = _build_llm(temperature=0.3)
    final_response = await llm_no_tools.ainvoke(messages)
    state["llm_response"] = _extract_llm_text(final_response.content)

    return state


# ---------------------------------------------------------------------------
# Node 6 — Persist the turn
# ---------------------------------------------------------------------------

async def save_hr_turn_node(state: HRChatState) -> HRChatState:
    """Persist user + assistant messages to recruitment-service (MySQL)."""
    try:
        await recruitment_api.save_message(
            session_id=state["session_id"],
            role="USER",
            content=state["query"],
        )
        raw_calls = state.get("function_calls")
        function_call_payload: Optional[str] = json.dumps(raw_calls, ensure_ascii=False) if raw_calls else None

        await recruitment_api.save_message(
            session_id=state["session_id"],
            role="ASSISTANT",
            content=state["llm_response"],
            function_call=function_call_payload,
        )
    except Exception as e:
        print(f"[HR Graph] Could not save turn: {e}")

    return state


# ---------------------------------------------------------------------------
# Node 7 — Format final response
# ---------------------------------------------------------------------------

def format_hr_response_node(state: HRChatState) -> HRChatState:
    """Package the final answer and metadata for the API layer."""
    state["final_answer"] = state["llm_response"]
    state["metadata"] = {
        "mode":                 state["mode"],
        "position_id":          state["position_id"],
        "is_cv_count_query":    state.get("is_cv_count_query", False),
        "cv_chunks_used":       len(state.get("cv_context", [])),
        "candidate_ids_count":  len(state.get("candidate_ids") or []),
        "sql_records_count":    len(state.get("sql_metadata", [])),
        "function_calls":       state.get("function_calls"),
        "retrieval_stats":      state.get("retrieval_stats", {}),
    }
    return state


# ---------------------------------------------------------------------------
# Graph construction
# ---------------------------------------------------------------------------

def create_hr_graph():
    workflow = StateGraph(HRChatState)

    workflow.add_node("load_hr_session_history", load_hr_session_history_node)
    workflow.add_node("load_candidate_scope",    load_candidate_scope_node)
    workflow.add_node("retrieve_hr_context",     retrieve_hr_context_node)
    workflow.add_node("build_hr_prompts",        build_hr_prompts_node)
    workflow.add_node("llm_hr_reasoning",        llm_hr_reasoning_node)
    workflow.add_node("save_hr_turn",            save_hr_turn_node)
    workflow.add_node("format_hr_response",      format_hr_response_node)

    workflow.set_entry_point("load_hr_session_history")
    workflow.add_edge("load_hr_session_history", "load_candidate_scope")
    workflow.add_edge("load_candidate_scope",    "retrieve_hr_context")
    workflow.add_edge("retrieve_hr_context",     "build_hr_prompts")
    workflow.add_edge("build_hr_prompts",        "llm_hr_reasoning")
    workflow.add_edge("llm_hr_reasoning",        "save_hr_turn")
    workflow.add_edge("save_hr_turn",            "format_hr_response")
    workflow.add_edge("format_hr_response",      END)

    return workflow.compile()


# ---------------------------------------------------------------------------
# Public interface
# ---------------------------------------------------------------------------

class HRChatbot:
    def __init__(self):
        self.graph = create_hr_graph()

    async def chat(
        self,
        query: str,
        session_id: str,
        hr_id: str,
        position_id: int,
        mode: Literal["HR_MODE", "CANDIDATE_MODE"]
    ) -> Dict[str, Any]:
        initial_state: HRChatState = {
            "query":                query,
            "hr_id":                hr_id,
            "session_id":           session_id,
            "position_id":          position_id,
            "mode":                 mode,
            "is_cv_count_query":    False,
            "candidate_ids":        None,
            "sql_metadata":         [],
            "conversation_history": [],
            "cv_context":           [],
            "retrieval_stats":      {},
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


hr_chatbot = HRChatbot()
