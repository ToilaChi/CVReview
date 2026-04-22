# Phân Tích Kỹ Thuật Chuyên Sâu: CVReview Chatbot System

> **Scope:** Candidate Chatbot + HR Chatbot | **Stack:** LangGraph + Gemini API + Qdrant + Spring Boot
> **Cập nhật lần cuối:** 2026-04-21

---

## Phần 1: Chẩn Đoán Root Cause

### 1.1 Vấn đề Intent Classification (Regex-Based)

**Root Cause trong `intent.py`:**

File 421 dòng với ~80 regex patterns hoạt động theo kiểu **Pattern Voting** — đếm số lần pattern match, không phải semantic understanding. Hệ quả:

| Triệu chứng | Root Cause |
|---|---|
| Trả lời không đúng trọng tâm | Intent sai → sai prompt template → sai context được fetch |
| Câu hỏi tự nhiên bị miss | Regex chỉ mạnh với keyword rõ. Câu như *"Intern attend tech talk được không?"* → không có pattern → fallback `jd_search` |
| Score tie → luôn về `jd_search` | `_semantic_fallback_classification()` line 370-371 hard-code default |
| Câu hỏi về CV count bị hallucinate | Không có intent cover → map `jd_search` → `JD_SEARCH_PROMPT` không có data về count → LLM bịa |

---

### 1.2 Vấn đề Full JD Text Fetch Không Cần Thiết

**Root Cause trong `candidate_graph.py` — `retrieve_context_node`:**

Hiện tại Small-to-Big luôn fetch full JD text từ Java API **mọi request** có `intent=jd_search`, kể cả khi scoring đã có cache:

```
Turn 1 — "Vị trí nào phù hợp?":
  retrieve_context_node → Small-to-Big → fetch FULL JD text  ✅ Đúng (cần cho scoring)
  scoring_node → Gemini Pro → scored_jobs → cache vào history  ✅

Turn 2 — "Lương Java Intern bao nhiêu?":
  retrieve_context_node → Small-to-Big → fetch FULL JD text lại  ❌ Lãng phí
  scoring_node → "Cache hit, skip" ✅ (cache đúng, nhưng retrieval sai)
```

**Hệ quả kép:** Tốn latency fetch API không cần thiết + context bloat (full JD text khi chỉ cần section "benefits").

---

### 1.3 Vấn đề Sensitive Data (Apply / Send Email)

**Root Cause trong `hr_graph.py` — `llm_hr_reasoning_node`:**

```python
# Hiện tại: tool_args đến thẳng từ LLM output, không enrich gì
tool_result = await tool_instance.ainvoke(tool_args)
```

`send_interview_email` yêu cầu `candidate_id`, `position_id`, `position_name` — đều là system data có sẵn trong `HRChatState` nhưng không được inject. LLM thiếu data → yêu cầu user cung cấp.

**Với Candidate chatbot:** `finalize_application` đã inject đúng `candidate_id` + `session_id` (line 392-396). Nhưng `position_id` vẫn do LLM tự extract từ text conversation → dễ nhầm khi user nói tên thay vì ID.

---

### 1.4 Vấn đề HR báo sai số CV (10 thay vì 5)

**Root Cause trong `hr_graph.py` — `_format_cv_context()`:**

Qdrant trả về **chunks**, không phải documents. 5 CVs × 2 chunks = 10 entries. `_format_cv_context()` format từng chunk thành `CV #1`, `CV #2`... → LLM nhìn thấy 10 "CV blocks" và đếm thành 10.

**Không phải hallucination thuần túy — là data presentation bug.**

---

### 1.5 Vấn đề Score Lệch Giữa Hai Service

**So sánh hai scoring prompts:**

| Dimension | `candidate_graph.py` | `LlmAnalysisService.java` |
|---|---|---|
| Model | `gemini-2.5-pro` | `gemini-2.5-flash` |
| Temperature | `0.0` | `0.1` |
| Công thức | Deduct từ 100: `-15/required miss`, `-5/nicetohave miss` | 3 categories: Core(60) + Depth(30) + Gaps(10) |
| Đánh giá project depth | ❌ Không có | ✅ Junior CRUD chỉ đạt 0-14 pts |
| "Gắt" với skill claim | Chỉ so skill count | "If not stated in CV, assume DOES NOT have it" |

Cùng một CV: `candidate_graph` cho 80 (ít miss), `LlmAnalysisService` cho 35-40 (Depth thấp vì junior project). **Hai thang đo hoàn toàn khác nhau về triết lý.**

---

## Phần 2: Hướng Giải Quyết Đã Được Thống Nhất

### 2.1 Intent Classification — Adaptive Routing 3 Tầng (Không Thêm API Call)

**Bỏ ý tưởng LLM Router tách biệt** — tốn +300-500ms, không cần thiết cho hệ thống này.

**Lý do giữ Regex (nhưng đơn giản hóa + bổ sung 2 tầng còn lại):**

Regex trong hệ thống này không phải để LLM "hiểu" câu hỏi — mà để quyết định **RETRIEVAL PATH**: search collection nào, filter gì, có cần trigger scoring không. LLM đủ thông minh để tự handle sub-intent nếu được prompt đúng.

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Tầng 1 — Hard Rules (microseconds, zero cost)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"apply" | "nộp đơn" | "i want to apply"
  → intent = jd_search, trigger finalize_application tool

"bao nhiêu cv" | "how many cv" | "upload bao nhiêu"
  → intent = general, trigger get_cv_summary tool (HR)

Scoring cache exists (scored_jobs not None) + follow-up question
  → skip full JD fetch, use Qdrant chunks

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Tầng 2 — Simplified Keyword Scoring (ms)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Giữ regex classifier nhưng giảm từ ~80 patterns → ~15 signal/intent
Chỉ giữ patterns có precision cao, bỏ các pattern overlap

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Tầng 3 — Adaptive System Prompt (zero extra cost)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Inject vào system_prompt của MAIN LLM call:
"Before responding, analyze what the user actually needs:
 - If asking about SALARY/BENEFITS → extract from JD benefits section only
 - If asking about PROCESS → focus on interview stages from JD
 - If asking COUNT/STATS → answer from provided data only, never estimate
 - If data is NOT in context → state explicitly what's missing"

LLM tự phân loại sub-intent, không cần route trước.
```

**Tại sao không cần LLM Router tách biệt:** Intent classification chỉ cần quyết định *retrieval strategy* — collection nào, filter gì. Sau khi context đã được fetch đúng, LLM chính (Flash) đủ thông minh để hiểu sub-intent và format câu trả lời phù hợp từ Tầng 3.

---

### 2.2 JD Retrieval — Dual Mode Dựa Trên Scoring Cache

**Quy tắc mới:**

| Điều kiện | Strategy | Lý do |
|---|---|---|
| `scored_jobs = None` (Turn 1) | Small-to-Big → fetch full JD text | Cần full context cho scoring chính xác |
| `scored_jobs` có cache (Turn 2+) | Qdrant chunks (section-based) | Chỉ cần section liên quan câu hỏi hiện tại |

```python
# candidate_graph.py — retrieve_context_node (logic mới)

has_scoring_cache = bool(state.get("scored_jobs"))

if intent in ("jd_search", "jd_analysis") and chunk_hits:
    if not has_scoring_cache:
        # Turn 1: fetch full JD text để scoring
        full_jd_list = await recruitment_api.get_position_details(position_ids)
        jd_context = [...]  # full jdText objects
        print("[Retriever] No cache → Small-to-Big full JD fetch")
    else:
        # Turn 2+: dùng Qdrant chunks (section-based, relevant)
        jd_context = chunk_hits
        print("[Retriever] Cache hit → section chunks, skip full JD fetch")
```

**Lợi ích kép:**
- Giảm latency từ Turn 2 trở đi (bỏ Java API call)
- Câu hỏi về salary → đúng chunk "benefits", câu hỏi về process → đúng chunk "recruitment"
- Qdrant semantic search thực sự **hữu ích hơn** full text cho follow-up questions

---

### 2.3 Unify Scoring Formula (Cả 2 Service)

**Quyết định:** Đồng bộ về công thức **3-category** của `LlmAnalysisService.java` vì có depth evaluation. **Điều chỉnh giảm nhẹ độ gắt** so với bản gốc để tránh underscoring quá mức.

**Target formula (áp dụng cho cả 2 nơi):**

```
SCORING SYSTEM (Total 100 pts):

1. Core Requirement Fit (Max 60 pts):
   - Start: 60 pts
   - Deduct 8 pts per missing REQUIRED skill (giảm từ 10 → 8)
   - Deduct 12 pts if candidate has < 70%% of required tech stack

2. Depth of Experience (Max 30 pts):
   - 22-30 pts: Led architectural decisions, measurable impact
   - 12-21 pts: Mid-level, implemented features with design rationale
   - 0-11 pts:  Junior/basic projects, CRUD only (giảm ngưỡng)

3. Professionalism & Gaps (Max 10 pts):
   - Deduct 4 pts for missing required degree (giảm từ 5 → 4)
   - Deduct 3 pts for poor CV structure

RULES:
- STRICT: If skill not explicitly stated in CV, assume NOT present
- NO HALLUCINATION: Do not infer from context
- Score 0 only if candidate clearly fails ALL minimum requirements
```

> **Lưu ý kỹ thuật:** Dùng `%%` trong Python f-string/`.formatted()` của Java để tránh `IllegalFormatConversionException`.

**File cần cập nhật:**
- `LlmAnalysisService.java` → `buildPrompt()`: Giảm deduction từ -10 → -8, giảm ngưỡng Depth
- `candidate_graph.py` → `scoring_node` → `scoring_prompt`: Thay toàn bộ bằng 3-category formula trên

---

### 2.4 Auto-Inject State vào Tool Args

#### HR Chatbot — `send_interview_email`

```python
# hr_graph.py — llm_hr_reasoning_node, tool execution loop

for call in response.tool_calls:
    tool_name = call["name"]
    tool_args = call["args"].copy()  # shallow copy, tránh mutate LLM output

    if tool_name == "send_interview_email":
        # Inject system data từ state, không để LLM tự điền
        tool_args["position_id"] = state["position_id"]

        # Nếu LLM chỉ biết tên candidate → resolve candidateId từ sql_metadata
        if not tool_args.get("candidate_id"):
            candidate_name = tool_args.get("candidate_name", "")
            resolved = _resolve_candidate_from_metadata(candidate_name, state["sql_metadata"])
            if resolved:
                tool_args["candidate_id"] = resolved["candidateId"]
    
    tool_result = await tool_instance.ainvoke(tool_args)
```

#### Candidate Chatbot — `finalize_application`

Đã inject đúng `candidate_id` + `session_id`. Cần bổ sung: nếu user nói tên position (không phải ID) → resolve từ `position_ref_map` trước khi invoke.

---

### 2.5 Fix CV Count Hallucination (HR Mode)

**Fix `_format_cv_context()` trong `hr_graph.py`:**

```python
def _format_cv_context(cv_context: List[Dict[str, Any]]) -> str:
    if not cv_context:
        return "No CV data found."
    
    # Deduplicate chunks → unique candidates
    seen_candidates: dict = {}
    for chunk in cv_context:
        cid = chunk.get("payload", {}).get("candidateId", "unknown")
        if cid not in seen_candidates:
            seen_candidates[cid] = []
        seen_candidates[cid].append(chunk)
    
    # Format theo candidate, không theo chunk
    parts = []
    for i, (cid, chunks) in enumerate(seen_candidates.items(), 1):
        chunk_texts = "\n".join(
            c.get("payload", {}).get("chunkText", "").strip() for c in chunks
        )
        parts.append(f"--- Candidate #{i} | ID: {cid} ---\n{chunk_texts}")
    
    return "\n\n".join(parts)
```

**Thêm tool `get_cv_summary` vào `hr_tools.py`:**

```python
@tool
async def get_cv_summary(position_id: int) -> str:
    """
    Lấy thống kê tổng quan CV cho một vị trí: tổng số, đã chấm, pass/fail.
    Gọi khi HR hỏi về số lượng CV, tỷ lệ đạt, hoặc phân bổ điểm.

    Args:
        position_id: ID vị trí cần thống kê (từ context phiên làm việc)
    """
    data = await recruitment_api.get_cv_statistics(position_id=position_id)
    total   = data.get("total", 0)
    scored  = data.get("scored", 0)
    passed  = data.get("passed", 0)
    failed  = scored - passed
    return (
        f"Vị trí ID {position_id}: {total} CV đã upload | "
        f"{scored} đã chấm | {passed} pass (≥75đ) | {failed} fail"
    )
```

> **Cần thêm endpoint** `GET /internal/positions/{id}/cv-statistics` vào Java `recruitment-service`.

---

## Phần 3: Bộ Intent & Kịch Bản Phản Hồi Mẫu

### 3.1 Intent Schema (Giữ Routing Paths Hiện Tại, Thêm Hard Rules)

#### Candidate — Mapping từ câu hỏi thực tế

| Intent Path | Câu hỏi thực tế cover | Retrieval Strategy |
|---|---|---|
| `jd_search` | "CV phù hợp vị trí nào?", "Apply Java Intern được không?", "So sánh độ phù hợp" | CV chunks + JD (full nếu không có cache, chunks nếu có cache) |
| `jd_analysis` | "Lương thế nào?", "Môi trường thế nào?", "Vòng phỏng vấn gồm mấy vòng?", "Có mentor không?" | JD chunks theo section |
| `cv_analysis` | "Tôi có skill gì?", "Điểm mạnh/yếu của tôi?", "Cần học gì để cải thiện?" | CV chunks only |
| `general` (hard rule) | "Tôi đã apply chưa?", "Trạng thái đơn thế nào?" | Tool `check_application_status` |

#### HR — Mapping từ câu hỏi thực tế

| Signal từ query | Route to | Tool Triggered |
|---|---|---|
| "top N", "điểm cao nhất", "lọc" | Qdrant semantic + SQL scores | Không |
| "so sánh A và B" | Fetch 2 candidate CV chunks | Không |
| "bao nhiêu CV", "upload", "tổng" | Hard rule → `get_cv_summary` | `get_cv_summary` |
| "gửi email", "mời phỏng vấn", "từ chối" | `send_interview_email` tool | `send_interview_email` |
| "CV của X có gì bất thường?" | `get_candidate_details` tool | `get_candidate_details` |

### 3.2 Kịch Bản Phản Hồi Mẫu

**Candidate Apply — Không cần cung cấp IDs:**
```
User:  "Tôi muốn nộp đơn vào Java Intern"
State: scored_jobs cache = [{positionId: 2, score: 85, ...}]
       position_ref_map  = {2: "Fresher Java Developer"}
       candidate_id      = "270e934c-..."

System: Hard rule → "apply/nộp đơn" → trigger finalize_application
        tool_args injected: position_id=2, candidate_id=<từ state>, session_id=<từ state>

Bot: "Tôi đã nộp đơn thành công cho vị trí Fresher Java Developer
     với điểm phù hợp 85/100. HR sẽ liên hệ bạn sớm!"
```

**HR Send Email — Không cần cung cấp candidateId:**
```
HR:    "Gửi email mời phỏng vấn Nguyễn Văn A vào 30/4 lúc 9h"
State: position_id      = 2
       sql_metadata     = [{candidateId: "abc-123", name: "Nguyen Van A", ...}]

System: LLM → gọi send_interview_email(candidate_name="Nguyễn Văn A", ...)
        Intercept → inject position_id=2 từ state
        Resolve → candidateId="abc-123" từ sql_metadata

Bot: "Đã gửi email mời phỏng vấn thành công tới Nguyễn Văn A."
```

**HR CV Count — Không hallucinate:**
```
HR:    "Tôi đã upload bao nhiêu CV?"
System: Hard rule → "bao nhiêu CV" → trigger get_cv_summary(position_id=2)
        API returns: {total: 5, scored: 5, passed: 3, failed: 2}

Bot: "Vị trí này có 5 CV đã upload. Tất cả đã được chấm:
     3 pass (≥75đ), 2 fail."
```

**Follow-up sau scoring:**
```
Turn 1: "Vị trí nào phù hợp?"
  → has_scoring_cache=False → full JD fetch → Gemini Pro scoring → cache

Turn 2: "Lương Java Intern bao nhiêu?"
  → has_scoring_cache=True → Qdrant semantic search → chunk "salary: 5-8M"
  → LLM đọc chunk → trả lời chính xác từ JD section
  (Không fetch lại full JD, không query Java API)
```

---

## Phần 4: Implementation Plan

### 4.1 Priority Matrix

| Priority | Item | Impact | Effort | Files |
|---|---|---|---|---|
| 🔴 P0 | Fix CV count: deduplicate chunks theo candidateId | High | Low | `hr_graph.py` |
| 🔴 P0 | Auto-inject `position_id` + resolve candidateId vào HR tool args | High | Low | `hr_graph.py` |
| 🔴 P0 | Dual-mode JD retrieval (full vs chunks theo cache) | High | Low | `candidate_graph.py` |
| 🔴 P0 | Unify scoring formula (3-category, giảm nhẹ deduction) | High | Medium | `candidate_graph.py`, `LlmAnalysisService.java` |
| 🟠 P1 | Thêm Adaptive System Prompt (Tầng 3) vào main LLM | High | Low | `prompts.py`, `hr_graph.py` |
| 🟠 P1 | Thêm hard rules cho apply + CV count vào Tầng 1 routing | High | Low | `candidate_graph.py`, `hr_graph.py` |
| 🟠 P1 | Thêm tool `get_cv_summary` + endpoint Java | Medium | Medium | `hr_tools.py`, recruitment-service |
| 🟡 P2 | Đơn giản hóa regex patterns (~80 → ~15 signal/intent) | Medium | Medium | `intent.py` |
| 🟡 P2 | Fix `build_jd_context()` truncation (1500 chars) | Medium | Low | `prompts.py` |
| 🟢 P3 | Thêm tool `check_application_status` cho Candidate | Low | Medium | `candidate_tools.py` |

### 4.2 Checklist Triển Khai

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Phase 0 — Quick Fixes (không restart lớn)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[ ] hr_graph.py: Refactor _format_cv_context() → deduplicate theo candidateId
[ ] hr_graph.py: Intercept tool_args trước ainvoke, inject position_id + resolve candidateId
[ ] candidate_graph.py: retrieve_context_node — check has_scoring_cache, split dual-mode
[ ] candidate_graph.py: Update scoring_prompt → 3-category formula + %% fix
[ ] LlmAnalysisService.java: Update buildPrompt() → giảm deduction -10→-8, ngưỡng Depth

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Phase 1 — Adaptive Routing
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[ ] prompts.py: Thêm ADAPTIVE_INSTRUCTION vào SYSTEM_PROMPT
[ ] hr_graph.py: Thêm ADAPTIVE_INSTRUCTION vào system_prompt của HR
[ ] candidate_graph.py: Thêm hard rules Tầng 1 (apply detection, quick-route)
[ ] hr_graph.py: Thêm hard rules Tầng 1 (cv count detection → get_cv_summary)
[ ] hr_tools.py: Thêm get_cv_summary tool
[ ] recruitment-service: Thêm endpoint GET /internal/positions/{id}/cv-statistics

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Phase 2 — Cleanup & Polish
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[ ] intent.py: Giảm regex patterns về ~15 signal/intent (bỏ overlap, giữ precision cao)
[ ] prompts.py: Fix build_jd_context() — bỏ truncation 1500 chars
[ ] prompts.py: Thêm sub-prompt cho BENEFITS, PROCESS, IMPROVE intent paths

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Phase 3 — New Capabilities
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[ ] candidate_tools.py: Thêm check_application_status tool
[ ] recruitment_api.py: Thêm API calls tương ứng
[ ] hr_tools.py: Thêm search_candidates_by_criteria tool
```

---

## Tóm Tắt Root Causes & Actions

| # | Vấn đề | Root Cause Chính Xác | Fix |
|---|---|---|---|
| 1 | Chatbot trả lời sai trọng tâm | Regex miss natural language → sai prompt → context loãng | Adaptive Routing 3 tầng + adaptive system prompt |
| 2 | HR báo 10 CV thay vì 5 | Format chunks → "CV #N" → LLM đếm chunks, không phải documents | Deduplicate theo candidateId + tool `get_cv_summary` |
| 3 | Phải nhập sensitive data để apply | Candidate tool chưa resolve position_id từ position_ref_map | Hard-route `apply` intent + inject từ state |
| 4 | Phải nhập sensitive data để send email | HR tool_args không được enrich từ HRChatState | Intercept + inject `position_id`, resolve `candidateId` từ `sql_metadata` |
| 5 | Score lệch 80 vs 35-40 | Hai công thức chấm hoàn toàn khác nhau (deduction vs category) | Unify 3-category formula, giảm nhẹ deduction ở cả 2 service |
| 6 | Full JD fetch lãng phí từ Turn 2 | retrieve_context_node không check scoring cache | Dual-mode retrieval theo `has_scoring_cache` |
