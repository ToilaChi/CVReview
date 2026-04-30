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
from qdrant_client.models import Filter, FieldCondition, MatchValue, MatchAny

from app.services.retriever import retriever, get_chunk_text
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

# Pattern to extract an explicit number from HR query (e.g. "top 10", "5 CVs")
_TOP_N_PATTERN = re.compile(r"\b(?:top\s*)?(\d+)\b", re.IGNORECASE)

_HR_DEFAULT_TOP_N = 10  # Fallback when HR does not specify a number


def _is_cv_count_query(query: str) -> bool:
    """Tầng 1: Detect CV/resume count queries to route to get_cv_summary tool."""
    return bool(_CV_COUNT_PATTERN.search(query))


def _extract_top_n_from_query(query: str) -> int:
    """
    Parse the number of candidates HR wants (e.g. 'top 5', '10 ứng viên').
    Returns the first non-zero integer found, capped at 50 to prevent runaway fetches.
    Falls back to _HR_DEFAULT_TOP_N when no number is present.
    """
    matches = _TOP_N_PATTERN.findall(query)
    for m in matches:
        n = int(m)
        if n > 0:
            return min(n, 50)
    return _HR_DEFAULT_TOP_N


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

    # ISSUE-01: track the cvIds discussed in prior turns so follow-up queries
    # (COMPARE / DETAIL) can pin-fetch them without re-running full reranking.
    active_cv_ids: List[int]
    hr_query_intent: str  # RANK | COMPARE | DETAIL | GENERAL

    # Phase 3: candidate_ids is REMOVED from Qdrant query path.
    # Qdrant filters by applied_position_ids directly (see retriever.py).
    # sql_metadata is still fetched for name/email/score display only.
    sql_metadata: List[Dict[str, Any]]
    cv_id_to_meta: Dict[int, Dict[str, Any]]  # fast lookup: cvId → metadata record

    # Email confirmation flow state
    pending_emails: Optional[List[Dict[str, Any]]]  # email args pending HR confirmation

    # Scoring flow state
    pending_scoring_candidates: Optional[List[Dict[str, Any]]]  # candidates queued for scoring

    # Session memory
    conversation_history: List[Dict[str, Any]]

    # RAG context
    cv_context: List[Dict[str, Any]]
    jd_context: List[Dict[str, Any]]
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
# ISSUE-01 helpers: intent detection + pinned-CV fetch
# ---------------------------------------------------------------------------

_COMPARE_PATTERN = re.compile(
    r"\b(so sánh|compare|đối chiếu|khác nhau|điểm khác|versus|vs\.?)\b",
    re.IGNORECASE,
)
_DETAIL_PATTERN = re.compile(
    r"\b(chi tiết|detail|thông tin về|tell me about|nói về|profile của|hồ sơ của)\b",
    re.IGNORECASE,
)
_RANK_PATTERN = re.compile(
    r"\b(top|rank|xếp hạng|tốt nhất|phù hợp nhất|liệt kê|danh sách|tìm)\b",
    re.IGNORECASE,
)


def detect_hr_query_intent(query: str, has_active_cv_ids: bool) -> str:
    """
    Classify HR query into one of four intents.

    COMPARE / DETAIL are only meaningful when there are already active_cv_ids
    from a previous turn. Without them we fall through to RANK so a fresh
    retrieval is triggered.
    """
    if has_active_cv_ids and _COMPARE_PATTERN.search(query):
        return "COMPARE"
    if has_active_cv_ids and _DETAIL_PATTERN.search(query):
        return "DETAIL"
    if _RANK_PATTERN.search(query):
        return "RANK"
    return "GENERAL"


async def _fetch_pinned_cv_context(
    cv_ids: List[int],
    qdrant_svc,
    cv_collection: str,
) -> List[Dict[str, Any]]:
    """
    Directly fetch all chunks for the given cvIds from Qdrant.
    Bypasses reranking — used when HR wants to compare/detail previously
    surfaced candidates so we guarantee the same set of CVs is returned.
    Returns chunks sorted by cvId then score descending.
    Keeps at most 6 chunks per cvId to avoid inflating the prompt.
    """
    MAX_CHUNKS_PER_CV = 6

    results = qdrant_svc.search_similar(
        collection_name=cv_collection,
        query_vector=[0.0] * 1024,  # dummy vector — filter is what matters here
        limit=len(cv_ids) * MAX_CHUNKS_PER_CV,
        score_threshold=0.0,
        filters=Filter(must=[
            FieldCondition(key="cvId", match=MatchAny(any=cv_ids)),
            FieldCondition(key="is_latest", match=MatchValue(value=True)),
        ]),
    )

    # Group and cap per cvId
    grouped: Dict[int, List[Dict[str, Any]]] = {}
    for chunk in results:
        cv_id = chunk.get("payload", {}).get("cvId")
        if cv_id is not None:
            grouped.setdefault(cv_id, []).append(chunk)

    pinned: List[Dict[str, Any]] = []
    for cv_id in cv_ids:  # preserve original ordering
        chunks = sorted(
            grouped.get(cv_id, []),
            key=lambda c: c.get("score", 0.0),
            reverse=True,
        )[:MAX_CHUNKS_PER_CV]
        pinned.extend(chunks)

    return pinned


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

        # Restore session cache from the most recent ASSISTANT turn
        for turn in reversed(history):
            if turn.get("role") == "ASSISTANT":
                func_data_str = turn.get("functionCall")
                if func_data_str:
                    try:
                        func_data = json.loads(func_data_str)
                        if not isinstance(func_data, dict):
                            break
                        if "pending_emails" in func_data:
                            state["pending_emails"] = func_data["pending_emails"]
                            print(f"[Cache Hit] Restored {len(state['pending_emails'])} pending_email(s)")
                        # ISSUE-01: restore active_cv_ids so follow-up COMPARE/DETAIL can pin-fetch
                        if "active_cv_ids" in func_data:
                            state["active_cv_ids"] = func_data["active_cv_ids"]
                            print(f"[Cache Hit] Restored active_cv_ids: {state['active_cv_ids']}")
                    except json.JSONDecodeError:
                        pass
                break
    except Exception as e:
        print(f"[HR Graph] Could not load history: {e}")
        state["conversation_history"] = []

    # Tầng 1: Detect CV count query before retrieval
    state["is_cv_count_query"] = _is_cv_count_query(state["query"])
    if state["is_cv_count_query"]:
        print("[Tầng 1] CV count query detected — will route to get_cv_summary tool.")

    # ISSUE-01: Classify HR query intent before retrieval so retrieve_hr_context_node can route
    state["hr_query_intent"] = detect_hr_query_intent(
        state["query"],
        has_active_cv_ids=bool(state.get("active_cv_ids")),
    )
    print(f"[HR Intent] {state['hr_query_intent']} | active_cv_ids={state.get('active_cv_ids', [])}")

    return state


# ---------------------------------------------------------------------------
# Node 2 — Load candidate/CV scope (BOTH modes)
# ---------------------------------------------------------------------------

async def load_candidate_scope_node(state: HRChatState) -> HRChatState:
    """
    Fetch SQL application metadata for name/email/score display (both modes).

    Phase 3 change: candidate_ids is NO LONGER resolved here for Qdrant.
    Qdrant now filters Candidate CVs via the `applied_position_ids` array
    stored on each Master CV payload — see retriever.retrieve_for_hr_mode_candidate.

    HR_MODE:
      - Loads HR-uploaded CV records; cv_id_to_meta keyed by appCvId.
      - Qdrant filter: positionId + sourceType=HR.

    CANDIDATE_MODE:
      - Loads Candidate-applied CV records for metadata display only.
      - Qdrant filter: applied_position_ids contains position_id + sourceType=CANDIDATE.
    """
    try:
        applications = await recruitment_api.get_applications(
            position_id=state["position_id"]
        )
        target_source = "HR" if state["mode"] == "HR_MODE" else "CANDIDATE"
        filtered_apps = [app for app in applications if app.get("sourceType") == target_source]

        # Fast cvId → metadata lookup so _format_cv_context can inject name/email
        # Phase 3 fix: CANDIDATE mode uses masterCvId (which Qdrant returns), HR mode uses appCvId.
        state["cv_id_to_meta"] = {}
        for app in filtered_apps:
            if target_source == "CANDIDATE" and app.get("masterCvId") is not None:
                state["cv_id_to_meta"][app["masterCvId"]] = app
            elif app.get("appCvId") is not None:
                state["cv_id_to_meta"][app["appCvId"]] = app

        state["sql_metadata"] = filtered_apps

        print(
            f"[HR Graph] {state['mode']}: {len(filtered_apps)} SQL record(s) loaded "
            f"(for metadata display — Qdrant filter is position-based)."
        )

    except Exception as e:
        print(f"[HR Graph] Could not load candidate scope: {e}")
        state["sql_metadata"] = []
        state["cv_id_to_meta"] = {}

    return state


# ---------------------------------------------------------------------------
# Node 3 — Retrieve HR context from Qdrant
# ---------------------------------------------------------------------------

async def retrieve_hr_context_node(state: HRChatState) -> HRChatState:
    """
    Branch on mode and hr_query_intent (ISSUE-01):

      COMPARE / DETAIL  → _fetch_pinned_cv_context (bypass reranking, jd_context=[])
      RANK / GENERAL    → full retriever pipeline (HR or Candidate mode)

    Skips Qdrant retrieval for Tầng 1 CV count queries (data comes from SQL).
    Dynamic top_n is parsed from the HR query so responses like 'top 5' or 'top 10' are honoured.
    """
    if state.get("is_cv_count_query"):
        state["cv_context"] = []
        state["jd_context"] = []
        state["retrieval_stats"] = {"note": "skipped_for_cv_count_query"}
        return state

    query           = state["query"]
    hr_query_intent = state.get("hr_query_intent", "RANK")
    active_cv_ids   = state.get("active_cv_ids") or []

    # ISSUE-01: Pin-fetch for COMPARE/DETAIL turns — guarantees same CV set is returned.
    # ISSUE-02: No JD context injected for these intents (not needed, saves tokens).
    if hr_query_intent in ("COMPARE", "DETAIL") and active_cv_ids:
        print(f"[HR Retrieve] intent={hr_query_intent} — fetching pinned {len(active_cv_ids)} CV(s)")
        cv_chunks = await _fetch_pinned_cv_context(
            cv_ids=active_cv_ids,
            qdrant_svc=retriever.qdrant_service,
            cv_collection=retriever.cv_collection,
        )
        state["cv_context"]      = cv_chunks
        state["jd_context"]      = []  # ISSUE-02: no JD needed for compare/detail
        state["retrieval_stats"] = {
            "strategy": "pinned_cv_fetch",
            "cv_ids": active_cv_ids,
            "chunks_fetched": len(cv_chunks),
        }
        return state

    top_n = _extract_top_n_from_query(query)
    print(f"[HR Retrieve] intent={hr_query_intent}, mode={state['mode']}, top_n={top_n}")

    if state["mode"] == "HR_MODE":
        result = await retriever.retrieve_for_hr_mode_hr(
            query=query,
            position_id=state["position_id"],
            top_n=top_n,
        )
    else:
        result = await retriever.retrieve_for_hr_mode_candidate(
            query=query,
            position_id=state["position_id"],
            top_n=top_n,
        )

    state["cv_context"]      = result.get("cv_context", [])
    state["jd_context"]      = result.get("jd_context", [])
    state["retrieval_stats"] = result.get("retrieval_stats", {})

    # ISSUE-01: Persist active_cv_ids from this RANK/GENERAL turn for follow-up
    new_active_ids = list({
        chunk.get("payload", {}).get("cvId")
        for chunk in state["cv_context"]
        if chunk.get("payload", {}).get("cvId") is not None
    })
    state["active_cv_ids"] = new_active_ids
    print(f"[HR Retrieve] Stored active_cv_ids={new_active_ids}")

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


def _format_jd_context(jd_context: List[Dict[str, Any]]) -> str:
    """Format retrieved JD chunks for the LLM prompt (Small-to-Big)."""
    if not jd_context:
        return "No Job Description data found for this position."

    # Extract unique position info (all chunks in a search usually belong to 1 position)
    pos_info = {}
    for chunk in jd_context:
        payload = chunk.get("payload", {})
        pid = payload.get("positionId")
        if pid and pid not in pos_info:
            pos_info[pid] = {
                "name": payload.get("positionName", "Unknown"),
                "chunks": []
            }
        if pid:
            section = payload.get("sectionName", "General")
            text = payload.get("chunkText", "").strip()
            if text:
                pos_info[pid]["chunks"].append(f"[{section}]\n{text}")

    parts = []
    for pid, info in pos_info.items():
        header = f"--- Job Description: {info['name']} (ID: {pid}) ---"
        body = "\n\n".join(info["chunks"])
        parts.append(f"{header}\n{body}")

    return "\n\n".join(parts)


def _format_sql_metadata(sql_metadata: List[Dict[str, Any]], mode: str) -> str:
    """
    Format application score rows from SQL as a compact reference table.
    Filters by mode so the LLM only sees relevant records.
    """
    if not sql_metadata:
        return ""

    rows = []
    for app in sql_metadata:
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

    query_lower = name_query.lower().strip()

    return [
        app for app in sql_metadata
        if query_lower in (app.get("candidateName") or "").lower()
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
    jd_text      = _format_jd_context(state.get("jd_context", []))
    sql_text     = _format_sql_metadata(state.get("sql_metadata", []), state["mode"])

    # Phase 4: Try to extract position name from JD context for system prompt injection
    position_name = "Unknown Position"
    if state.get("jd_context"):
        for chunk in state["jd_context"]:
            name = chunk.get("payload", {}).get("positionName")
            if name:
                position_name = name
                break

    # If there is a pending email confirmation, inject it prominently
    pending_email = state.get("pending_email")
    pending_note  = ""
    if pending_email:
        pending_note = (
            f"\n\nPENDING EMAIL CONFIRMATION:\n"
            f"HR requested to send email to: {pending_email.get('candidate_name')} "
            f"({pending_email.get('candidate_email')})\n"
            f"Position: {pending_email.get('position_name')}\n"
            f"Type: {pending_email.get('email_type')}\n"
            f"If HR confirms, call send_interview_email with these exact details."
        )

    system_prompt = f"""You are a Senior HR Talent Acquisition Specialist assisting with recruitment decisions.
Current mode: {mode_label} | Position: {position_name} (ID: {state['position_id']})

Guidelines:
- Respond concisely and professionally — as an experienced recruiter, not a chatbot.
- Base all assessments strictly on the CV data and scores provided — never fabricate candidate details.
- When HR requests to send an email: FIRST confirm the recipient name and email address before invoking send_interview_email.
- When HR requests detailed candidate information, invoke the `get_candidate_details` tool.
- When HR asks about CV count or statistics, invoke the `get_cv_summary` tool.
- When HR wants to filter/rank candidates, invoke the `search_candidates_by_criteria` tool.
- When HR requests to score/evaluate/chấm điểm candidates, invoke the `evaluate_candidates` tool.
- NEVER reveal raw UUIDs or system IDs to HR in your response.
{_ADAPTIVE_INSTRUCTION}{pending_note}"""

    user_prompt = f"""## Conversation History:
{history_text if history_text else "(New session)"}

## CV Data Retrieved from System:
{cv_text}
"""
    # ISSUE-02: Only inject JD block when data is actually present — prevents
    # "No Job Description data found" noise on COMPARE/DETAIL turns.
    if state.get("jd_context"):
        user_prompt = (
            f"## Conversation History:\n{history_text if history_text else '(New session)'}\n\n"
            f"## Job Description Context:\n{jd_text}\n\n"
            f"## CV Data Retrieved from System:\n{cv_text}\n"
        )

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
    pending_emails = state.get("pending_emails")
    if pending_emails:
        if _EMAIL_CONFIRM_PATTERN.search(state["query"]):
            print(f"[Email Confirm] HR confirmed — executing {len(pending_emails)} send_interview_email(s).")
            tool_map  = {t.name: t for t in HR_TOOLS}
            email_tool = tool_map.get("send_interview_email")
            
            results = []
            function_calls = []
            
            if email_tool:
                for pe in pending_emails:
                    try:
                        res = await email_tool.ainvoke(pe)
                        results.append(str(res))
                        function_calls.append({"name": "send_interview_email", "arguments": pe, "result": res})
                    except Exception as e:
                        err_msg = f"Lỗi khi gửi email tới {pe.get('candidate_name')}: {str(e)}"
                        results.append(err_msg)
                        function_calls.append({"name": "send_interview_email", "arguments": pe, "result": err_msg})
            
            state["llm_response"] = "\n".join(results)
            state["function_calls"] = function_calls
            state["pending_emails"] = None
            return state
        else:
            # HR didn't confirm. Clear pending emails and let LLM handle the new query.
            print("[Email Confirm] HR did not confirm. Clearing pending_emails.")
            state["pending_emails"] = None

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
    new_pending_emails = []
    tool_map  = {t.name: t for t in HR_TOOLS}
    messages.append(response)

    for call in response.tool_calls:
        tool_name = call["name"]
        tool_args = call["args"].copy()

        # Auto-inject position_id and mode
        if tool_name in ("send_interview_email", "get_candidate_details", "get_cv_summary", "search_candidates_by_criteria"):
            if not tool_args.get("position_id"):
                tool_args["position_id"] = state["position_id"]
                print(f"[Tool Inject] position_id={state['position_id']} → {tool_name}")
            if tool_name in ("search_candidates_by_criteria", "get_cv_summary"):
                tool_args["mode"] = state["mode"]

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
            tool_args["app_cv_id"]       = int(matched.get("appCvId") or 0)
            tool_args["candidate_id"]    = str(matched.get("candidateId") or matched.get("appCvId", ""))
            tool_args["candidate_email"] = tool_args.get("candidate_email") or matched.get("candidateEmail", "")
            tool_args["candidate_name"]  = matched.get("candidateName", candidate_name)

            # Store pending email
            new_pending_emails.append(tool_args)
            continue  # Do NOT execute the tool yet

        # -----------------------------------------------------------------------
        # Intercept evaluate_candidates tool — route to hr_scoring_node
        # -----------------------------------------------------------------------
        if tool_name == "evaluate_candidates":
            # Populate pending_scoring_candidates from current cv_context + sql_metadata
            cv_id_to_meta = state.get("cv_id_to_meta", {})
            cv_ids_in_context: set = set()
            for chunk in state.get("cv_context", []):
                cid = chunk.get("payload", {}).get("cvId")
                if cid is not None:
                    cv_ids_in_context.add(cid)

            pending = []
            for cv_id in cv_ids_in_context:
                meta = cv_id_to_meta.get(cv_id, {})
                pending.append({
                    "cvId":          cv_id,
                    "appCvId":       meta.get("appCvId"),
                    "candidateName": meta.get("candidateName", f"CV-{cv_id}"),
                })

            state["pending_scoring_candidates"] = pending
            print(f"[Scoring] Intercepted evaluate_candidates → {len(pending)} candidate(s) queued")
            continue  # Do not execute the mock tool; hr_scoring_node will handle it

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

    if new_pending_emails:
        state["pending_emails"] = new_pending_emails
        lines = ["Bạn có chắc muốn gửi email tới các ứng viên sau không?"]
        for pe in new_pending_emails:
            lines.append(f"- **{pe.get('candidate_name')}** ({pe.get('candidate_email')})")
        lines.append("\nGõ 'Đồng ý' để xác nhận hoặc 'Huỷ' để bỏ qua.")
        state["llm_response"] = "\n".join(lines)
        print(f"[Email Confirm] Pending confirmation for {len(new_pending_emails)} candidate(s)")
        state["function_calls"] = None
        return state

    state["function_calls"] = executed_calls

    # Second LLM call to synthesise tool results
    llm_no_tools = _build_llm(temperature=0.3)
    final_response = await llm_no_tools.ainvoke(messages)
    state["llm_response"] = _extract_llm_text(final_response.content)

    return state


# ---------------------------------------------------------------------------
# Node 5b — HR Scoring Node (triggered when evaluate_candidates is called)
# ---------------------------------------------------------------------------

async def hr_scoring_node(state: HRChatState) -> HRChatState:
    """
    Scores each candidate in state['pending_scoring_candidates'] against the JD.
    Flow: LLM scoring prompt → save to DB via /applications/evaluate → return scorecard.
    """
    candidates = state.get("pending_scoring_candidates") or []
    jd_context  = state.get("jd_context", [])

    if not candidates:
        state["llm_response"] = "Không có ứng viên nào trong ngữ cảnh hiện tại để chấm điểm. Vui lòng thực hiện tìm kiếm trước."
        state["pending_scoring_candidates"] = None
        return state

    # Build JD text for the scoring prompt
    jd_text = "\n\n".join(
        get_chunk_text(c.get("payload", {})) for c in jd_context
    ).strip() or "(JD not available)"

    llm = _build_llm(temperature=0.1)  # low temp for deterministic scoring
    scoring_results = []
    saved_count = 0

    for candidate in candidates:
        cv_id   = candidate.get("cvId")
        app_cv_id = candidate.get("appCvId")
        name    = candidate.get("candidateName", f"CV-{cv_id}")

        # Gather all chunks for this candidate from cv_context
        cv_chunks = [
            c for c in state.get("cv_context", [])
            if c.get("payload", {}).get("cvId") == cv_id
        ]
        cv_text = "\n\n".join(
            f"[{c.get('payload',{}).get('section','?')}]\n{get_chunk_text(c.get('payload', {}))}"
            for c in cv_chunks
        ).strip() or "(CV content not available)"

        scoring_prompt = f"""You are a senior technical recruiter performing a structured CV evaluation.

## Job Description:
{jd_text}

## Candidate CV ({name}):
{cv_text}

Evaluate this candidate and respond with ONLY a JSON object (no markdown) in this exact format:
{{
  "technicalScore": <integer 0-100>,
  "experienceScore": <integer 0-100>,
  "overallStatus": "<EXCELLENT_MATCH|GOOD_MATCH|POTENTIAL|POOR_FIT>",
  "feedback": "<2-3 sentence summary>",
  "skillMatch": "<comma-separated matched skills>",
  "skillMiss": "<comma-separated missing skills or 'None'>",
  "learningPath": "<recommended path if POTENTIAL/POOR_FIT, else null>"
}}"""

        print("HR scoring_prompt: ", scoring_prompt)

        try:
            response = await llm.ainvoke([HumanMessage(content=scoring_prompt)])
            raw = _extract_llm_text(response.content).strip()

            # Strip markdown code fences if LLM adds them
            if raw.startswith("```"):
                raw = raw.split("\n", 1)[-1].rsplit("```", 1)[0].strip()

            import json as _json
            score_data = _json.loads(raw)

            # Persist to DB if we have an appCvId
            if app_cv_id:
                try:
                    await recruitment_api.evaluate_application(
                        app_cv_id=app_cv_id,
                        position_id=state["position_id"],
                        technical_score=score_data.get("technicalScore", 0),
                        experience_score=score_data.get("experienceScore", 0),
                        overall_status=score_data.get("overallStatus", "POOR_FIT"),
                        feedback=score_data.get("feedback", ""),
                        skill_match=score_data.get("skillMatch", ""),
                        skill_miss=score_data.get("skillMiss", ""),
                        learning_path=score_data.get("learningPath"),
                        session_id=state["session_id"],
                    )
                    saved_count += 1
                except Exception as save_err:
                    print(f"[Scoring] Failed to save score for {name}: {save_err}")

            status_icon = {
                "EXCELLENT_MATCH": "🌟",
                "GOOD_MATCH": "✅",
                "POTENTIAL": "🟡",
                "POOR_FIT": "❌",
            }.get(score_data.get("overallStatus", ""), "•")

            scoring_results.append(
                f"**{name}** {status_icon}\n"
                f"• Kỹ thuật: {score_data.get('technicalScore')}/100 | "
                f"Kinh nghiệm: {score_data.get('experienceScore')}/100\n"
                f"• Nhận xét: {score_data.get('feedback')}\n"
                f"• Phù hợp: {score_data.get('skillMatch')}\n"
                f"• Còn thiếu: {score_data.get('skillMiss')}"
            )
        except Exception as e:
            scoring_results.append(f"**{name}**: Lỗi khi chấm điểm — {str(e)}")
            print(f"[Scoring] Error scoring {name}: {e}")

    summary_header = (
        f"📊 **Kết quả chấm điểm {len(scoring_results)} ứng viên** "
        f"(đã lưu {saved_count}/{len(scoring_results)} vào hệ thống):\n"
    )
    state["llm_response"] = summary_header + "\n\n".join(scoring_results)
    state["pending_scoring_candidates"] = None
    state["function_calls"] = [{"name": "hr_scoring", "candidates_scored": len(scoring_results), "saved": saved_count}]
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
        raw_calls      = state.get("function_calls")
        pending_emails = state.get("pending_emails")
        active_cv_ids  = state.get("active_cv_ids") or []

        if raw_calls:
            # ISSUE-01: embed active_cv_ids into the function_call payload so it
            # survives session reload and can be restored on the next turn.
            payload_dict = (
                raw_calls if isinstance(raw_calls, dict)
                else {"calls": raw_calls}
            )
            payload_dict["active_cv_ids"] = active_cv_ids
            function_call_payload: Optional[str] = json.dumps(payload_dict, ensure_ascii=False)
        elif pending_emails:
            cache: dict = {"pending_emails": pending_emails}
            if active_cv_ids:
                cache["active_cv_ids"] = active_cv_ids
            function_call_payload = json.dumps(cache, ensure_ascii=False)
        elif active_cv_ids:
            function_call_payload = json.dumps(
                {"active_cv_ids": active_cv_ids}, ensure_ascii=False
            )
        else:
            function_call_payload = None

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
        "mode":              state["mode"],
        "position_id":       state["position_id"],
        "is_cv_count_query": state.get("is_cv_count_query", False),
        "cv_chunks_used":    len(state.get("cv_context", [])),
        "sql_records_count": len(state.get("sql_metadata", [])),
        "function_calls":    state.get("function_calls"),
        "retrieval_stats":   state.get("retrieval_stats", {}),
        "pending_emails":    state.get("pending_emails"),
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
    workflow.add_node("hr_scoring",              hr_scoring_node)
    workflow.add_node("save_hr_turn",            save_hr_turn_node)
    workflow.add_node("format_hr_response",      format_hr_response_node)

    workflow.set_entry_point("load_hr_session_history")
    workflow.add_edge("load_hr_session_history", "load_candidate_scope")
    workflow.add_edge("load_candidate_scope",    "retrieve_hr_context")
    workflow.add_edge("retrieve_hr_context",     "build_hr_prompts")
    workflow.add_edge("build_hr_prompts",        "llm_hr_reasoning")

    # Conditional routing: if scoring was triggered, go to hr_scoring_node first
    def _route_after_reasoning(state: HRChatState) -> str:
        if state.get("pending_scoring_candidates"):
            return "hr_scoring"
        return "save_hr_turn"

    workflow.add_conditional_edges(
        "llm_hr_reasoning",
        _route_after_reasoning,
        {"hr_scoring": "hr_scoring", "save_hr_turn": "save_hr_turn"}
    )
    workflow.add_edge("hr_scoring",              "save_hr_turn")
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
            "query":                       query,
            "hr_id":                       hr_id,
            "session_id":                  session_id,
            "position_id":                 position_id,
            "mode":                        mode,
            "is_cv_count_query":           False,
            "sql_metadata":                [],
            "cv_id_to_meta":               {},
            "pending_emails":              None,
            "pending_scoring_candidates":  None,
            "conversation_history":        [],
            "active_cv_ids":               [],        # ISSUE-01
            "hr_query_intent":             "RANK",    # ISSUE-01
            "cv_context":                  [],
            "jd_context":                  [],
            "retrieval_stats":             {},
            "system_prompt":               "",
            "user_prompt":                 "",
            "llm_response":                "",
            "function_calls":              None,
            "final_answer":                "",
            "metadata":                    {},
        }

        final_state = await self.graph.ainvoke(initial_state, {"recursion_limit": 50})

        return {
            "answer":   final_state["final_answer"],
            "metadata": final_state["metadata"],
        }


hr_chatbot = HRChatbot()
