"""
LangGraph workflow for the HR chatbot.
Supports two modes driven by HRChatState.mode:
  - HR_MODE:        Qdrant filter on positionId + sourceType=HR
  - CANDIDATE_MODE: Resolve candidateIds from SQL → Qdrant filter on candidateId

Phase 4 changes:
  - load_candidate_scope_node: fetch sql_metadata for BOTH modes (keyed by appCvId).
  - _format_cv_context: group by cvId, look up candidateName/email from sql_metadata.
  - llm_hr_reasoning_node: mandatory email confirmation flow + duplicate-name disambiguation.
  - search_candidates_by_criteria: supports top_k parameter.
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

# Pattern to detect HR confirming an email send
_EMAIL_CONFIRM_PATTERN = re.compile(
    r"\b(đồng ý|đồng y|xác nhận|xac nhan|confirm|yes|gửi đi|gui di|ok|okay|send it|chắc chắn)\b",
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
    candidate_ids: Optional[List[str]]       # CANDIDATE_MODE: candidateIds for Qdrant filter
    # sql_metadata: keyed by appCvId (int) → full record dict
    # Works for both modes: HR CVs have no candidateId but always have appCvId (=cvId in Qdrant)
    sql_metadata: List[Dict[str, Any]]
    cv_id_to_meta: Dict[int, Dict[str, Any]]  # fast lookup: cvId → metadata record

    # Email confirmation flow state
    pending_email: Optional[Dict[str, Any]]  # email args waiting for HR confirmation

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
# Node 2 — Load candidate/CV scope (BOTH modes)
# ---------------------------------------------------------------------------

async def load_candidate_scope_node(state: HRChatState) -> HRChatState:
    """
    Fetch sql_metadata for the current position for BOTH modes.

    HR_MODE:
      - Fetches all CVs for the position (sourceType=HR from DB, but we load all and filter by mode).
      - candidateIds = None (Qdrant filter uses positionId + sourceType=HR).
      - Builds cv_id_to_meta keyed by appCvId so _format_cv_context can map cvId → name.

    CANDIDATE_MODE:
      - Fetches all Candidate-applied CVs for the position.
      - candidateIds = list of candidateIds for Qdrant MatchAny filter.
      - Builds cv_id_to_meta keyed by appCvId for same name-mapping.

    In both cases appCvId == cvId stored in Qdrant payload, so the lookup always works.
    """
    try:
        applications = await recruitment_api.get_applications(
            position_id=state["position_id"]
        )
        state["sql_metadata"] = applications

        # Build fast cvId → metadata lookup (works regardless of mode)
        state["cv_id_to_meta"] = {
            app["appCvId"]: app
            for app in applications
            if app.get("appCvId") is not None
        }

        if state["mode"] == "CANDIDATE_MODE":
            # For Qdrant filter: use candidateIds of CANDIDATE-type CVs only
            state["candidate_ids"] = [
                app["candidateId"]
                for app in applications
                if app.get("sourceType") == "CANDIDATE" and app.get("candidateId")
            ]
            print(f"[HR Graph] CANDIDATE_MODE: {len(state['candidate_ids'])} candidate(s) in scope.")
        else:
            state["candidate_ids"] = None
            print(f"[HR Graph] HR_MODE: {len(applications)} CV(s) loaded from SQL.")

    except Exception as e:
        print(f"[HR Graph] Could not load candidate scope: {e}")
        state["candidate_ids"] = []
        state["sql_metadata"] = []
        state["cv_id_to_meta"] = {}

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


def _format_cv_context(
    cv_context: List[Dict[str, Any]],
    cv_id_to_meta: Dict[int, Dict[str, Any]]
) -> str:
    """
    Format retrieved CV chunks for the LLM prompt.

    Groups chunks by cvId (not candidateId — HR CVs have no candidateId).
    Uses cv_id_to_meta to inject the candidate's real name and email into
    each block, so the LLM can answer "Ai tên Minh?" correctly.

    Log the section name of each chunk for debugging.
    """
    if not cv_context:
        return "No CV data found for the current filter criteria."

    # Group chunks by cvId
    cv_chunks: Dict[int, List[str]] = {}
    cv_best_score: Dict[int, float] = {}

    for chunk in cv_context:
        payload = chunk.get("payload", {})
        cv_id   = payload.get("cvId")
        section = payload.get("section", "Unknown")
        text    = payload.get("chunkText", "").strip()
        score   = chunk.get("score", 0.0)

        if cv_id is None:
            continue

        print(f"[Retrieval] CV chunk: cvId={cv_id}, section={section}, score={score:.2f}")

        if cv_id not in cv_chunks:
            cv_chunks[cv_id] = []
            cv_best_score[cv_id] = score

        if text:
            cv_chunks[cv_id].append(f"[{section}]\n{text}")
        if score > cv_best_score[cv_id]:
            cv_best_score[cv_id] = score

    parts = []
    for i, (cv_id, texts) in enumerate(cv_chunks.items(), 1):
        meta  = cv_id_to_meta.get(cv_id, {})
        name  = meta.get("candidateName") or "Unknown"
        email = meta.get("candidateEmail") or "N/A"
        score_val = meta.get("score")
        score_str = f"{score_val}/100" if score_val is not None else "Not scored"

        header = (
            f"--- Candidate #{i}: {name} | Email: {email} | "
            f"AI Score: {score_str} | cvId: {cv_id} | "
            f"Similarity: {cv_best_score[cv_id]:.2f} ---"
        )
        body = "\n".join(texts)
        parts.append(f"{header}\n{body}")

    total = len(cv_chunks)
    return f"[{total} unique candidate(s)]\n\n" + "\n\n".join(parts)


def _format_sql_metadata(sql_metadata: List[Dict[str, Any]], mode: str) -> str:
    """
    Format application score rows from SQL as a compact reference table.
    Filters by mode so the LLM only sees relevant records.
    """
    if not sql_metadata:
        return ""

    target_source = "HR" if mode == "HR_MODE" else "CANDIDATE"
    rows = []
    for app in sql_metadata:
        if app.get("sourceType") != target_source:
            continue
        name  = app.get("candidateName", "N/A")
        email = app.get("candidateEmail", "N/A")
        score = app.get("score", "Not scored")
        cv_id = app.get("appCvId", "N/A")
        rows.append(f"• {name} | Email: {email} | Score: {score} | cvId: {cv_id}")

    return "\n".join(rows)


def _resolve_candidates_by_name(
    name_query: str,
    sql_metadata: List[Dict[str, Any]],
    mode: str
) -> List[Dict[str, Any]]:
    """
    Return ALL records matching a partial name query — used for email disambiguation.
    Filters by mode so HR_MODE only searches HR-uploaded CVs.
    """
    if not name_query or not sql_metadata:
        return []

    target_source = "HR" if mode == "HR_MODE" else "CANDIDATE"
    query_lower = name_query.lower().strip()

    return [
        app for app in sql_metadata
        if app.get("sourceType") == target_source
        and query_lower in (app.get("candidateName") or "").lower()
    ]


# Adaptive system prompt — Tầng 3
_ADAPTIVE_INSTRUCTION = """
Before responding, silently determine what HR actually needs:
- COUNT/STATS question → answer with exact numbers from provided data ONLY; never estimate
- SALARY/BENEFITS question → extract only from JD text; never invent numbers
- EMAIL/ACTION request → NEVER send email directly; always confirm recipient first
- CANDIDATE COMPARISON → reference specific CV data for each candidate
- If required data is NOT in context → state explicitly what is missing

Every factual claim MUST trace to a CV chunk, SQL record, or JD section shown above.
NEVER fabricate candidate details, scores, or contact information.
NEVER expose raw UUIDs or database IDs in your response.
"""


def build_hr_prompts_node(state: HRChatState) -> HRChatState:
    """Assemble system + user prompts for the HR LLM call."""
    mode_label   = "HR Mode (sourced CVs)" if state["mode"] == "HR_MODE" else "Candidate Mode (inbound applications)"
    history_text = _format_history(state.get("conversation_history", []))
    cv_text      = _format_cv_context(state.get("cv_context", []), state.get("cv_id_to_meta", {}))
    sql_text     = _format_sql_metadata(state.get("sql_metadata", []), state["mode"])

    # If there is a pending email confirmation, inject it prominently
    pending_email = state.get("pending_email")
    pending_note  = ""
    if pending_email:
        pending_note = (
            f"\n\n⚠️ PENDING EMAIL CONFIRMATION:\n"
            f"HR requested to send email to: {pending_email.get('candidate_name')} "
            f"({pending_email.get('candidate_email')})\n"
            f"Position: {pending_email.get('position_name')}\n"
            f"Type: {pending_email.get('email_type')}\n"
            f"If HR confirms, call send_interview_email with these exact details."
        )

    system_prompt = f"""You are a Senior HR Talent Acquisition Specialist assisting with recruitment decisions.
Current mode: {mode_label} | Position ID: {state['position_id']}

Guidelines:
- Respond concisely and professionally — as an experienced recruiter, not a chatbot.
- Base all assessments strictly on the CV data and scores provided — never fabricate candidate details.
- When HR requests to send an email: FIRST confirm the recipient name and email address before invoking send_interview_email.
- When HR requests detailed candidate information, invoke the `get_candidate_details` tool.
- When HR asks about CV count or statistics, invoke the `get_cv_summary` tool.
- When HR wants to filter/rank candidates, invoke the `search_candidates_by_criteria` tool.
- NEVER reveal raw UUIDs or system IDs to HR in your response.
{_ADAPTIVE_INSTRUCTION}{pending_note}"""

    user_prompt = f"""## Conversation History:
{history_text if history_text else "(New session)"}

## CV Data Retrieved from System:
{cv_text}
"""
    if sql_text:
        user_prompt += f"\n## Application Records from Database:\n{sql_text}\n"

    user_prompt += f"\n## HR Question:\n{state['query']}"

    state["system_prompt"] = system_prompt
    state["user_prompt"]   = user_prompt
    return state


# ---------------------------------------------------------------------------
# Node 5 — LLM reasoning with tool loop + mandatory email confirm
# ---------------------------------------------------------------------------

async def llm_hr_reasoning_node(state: HRChatState) -> HRChatState:
    """
    Gemini call with HR_TOOLS bound.

    Email confirmation flow:
    1. If HR says something like "Gửi email cho Nguyễn Văn A":
       - Resolve name in sql_metadata.
       - If 0 matches → ask HR to clarify.
       - If 2+ matches → list them with email, ask HR which one.
       - If 1 match  → store in pending_email, ask HR to confirm.
    2. If pending_email exists AND HR's current query is a confirmation phrase:
       - Execute send_interview_email with the stored args.
       - Clear pending_email.
    3. Otherwise → normal tool execution flow.
    """
    llm = _build_llm(temperature=0.3).bind_tools(HR_TOOLS)
    messages = [
        SystemMessage(content=state["system_prompt"]),
        HumanMessage(content=state["user_prompt"])
    ]

    response = await llm.ainvoke(messages)
    state["function_calls"] = None

    # -----------------------------------------------------------------------
    # Handle email confirmation turn (HR says "đồng ý", "ok", etc.)
    # -----------------------------------------------------------------------
    pending_email = state.get("pending_email")
    if pending_email and _EMAIL_CONFIRM_PATTERN.search(state["query"]):
        print("[Email Confirm] HR confirmed — executing send_interview_email.")
        tool_map  = {t.name: t for t in HR_TOOLS}
        email_tool = tool_map.get("send_interview_email")
        if email_tool:
            try:
                result = await email_tool.ainvoke(pending_email)
                state["llm_response"] = result
                state["function_calls"] = [{"name": "send_interview_email", "arguments": pending_email, "result": result}]
            except Exception as e:
                state["llm_response"] = f"Lỗi khi gửi email: {str(e)}"
        state["pending_email"] = None
        return state

    # -----------------------------------------------------------------------
    # No tool calls → plain text answer
    # -----------------------------------------------------------------------
    if not response.tool_calls:
        state["llm_response"] = _extract_llm_text(response.content)
        return state

    # -----------------------------------------------------------------------
    # Tool execution loop with auto-injection
    # -----------------------------------------------------------------------
    executed_calls = []
    tool_map  = {t.name: t for t in HR_TOOLS}
    messages.append(response)

    for call in response.tool_calls:
        tool_name = call["name"]
        tool_args = call["args"].copy()

        # Auto-inject position_id
        if tool_name in ("send_interview_email", "get_candidate_details", "get_cv_summary", "search_candidates_by_criteria"):
            if not tool_args.get("position_id"):
                tool_args["position_id"] = state["position_id"]
                print(f"[Tool Inject] position_id={state['position_id']} → {tool_name}")

        # -----------------------------------------------------------------------
        # Email: Resolve name → mandatory confirmation before sending
        # -----------------------------------------------------------------------
        if tool_name == "send_interview_email":
            candidate_name = tool_args.get("candidate_name", "")
            matches = _resolve_candidates_by_name(
                candidate_name, state.get("sql_metadata", []), state["mode"]
            )

            if len(matches) == 0:
                state["llm_response"] = (
                    f"Tôi không tìm thấy ứng viên nào tên '{candidate_name}' "
                    f"trong danh sách vị trí này. Vui lòng kiểm tra lại tên."
                )
                return state

            if len(matches) > 1:
                # Disambiguation: list all matches with email
                lines = [f"Tôi tìm thấy {len(matches)} ứng viên tên '{candidate_name}':"]
                for idx, m in enumerate(matches, 1):
                    lines.append(f"{idx}. {m.get('candidateName')} — {m.get('candidateEmail')}")
                lines.append("\nBạn muốn gửi email cho ai? Vui lòng chỉ rõ địa chỉ email.")
                state["llm_response"] = "\n".join(lines)
                return state

            # Exactly 1 match — fill in resolved data, then ask HR to confirm
            matched = matches[0]
            tool_args["candidate_id"]    = matched.get("candidateId") or matched.get("appCvId", "")
            tool_args["candidate_email"] = tool_args.get("candidate_email") or matched.get("candidateEmail", "")
            tool_args["candidate_name"]  = matched.get("candidateName", candidate_name)

            # Store pending email and ask for confirmation
            state["pending_email"] = tool_args
            state["llm_response"] = (
                f"Bạn có chắc muốn gửi email **{tool_args.get('email_type', 'INTERVIEW_INVITE')}** "
                f"tới **{tool_args['candidate_name']}** ({tool_args['candidate_email']}) không?\n"
                f"Gõ 'Đồng ý' để xác nhận hoặc 'Huỷ' để bỏ qua."
            )
            print(f"[Email Confirm] Pending confirmation for {tool_args['candidate_name']} ({tool_args['candidate_email']})")
            return state  # Do NOT execute the tool yet

        # -----------------------------------------------------------------------
        # All other tools — execute normally
        # -----------------------------------------------------------------------
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

    # Second LLM call to synthesise tool results
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
        "mode":                state["mode"],
        "position_id":         state["position_id"],
        "is_cv_count_query":   state.get("is_cv_count_query", False),
        "cv_chunks_used":      len(state.get("cv_context", [])),
        "candidate_ids_count": len(state.get("candidate_ids") or []),
        "sql_records_count":   len(state.get("sql_metadata", [])),
        "function_calls":      state.get("function_calls"),
        "retrieval_stats":     state.get("retrieval_stats", {}),
        "pending_email":       state.get("pending_email"),
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
            "cv_id_to_meta":        {},
            "pending_email":        None,
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
