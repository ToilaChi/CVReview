# CV Review Chatbot

Risk Analysis & Implementation Plan

*Codebase Audit · April 2026*

---

# PART 1 — Risk Analysis

13 issues were identified by cross-referencing hr_question.md, candidate_question.md, and the full codebase (candidate_graph.py, hr_graph.py, retriever.py, reranker.py, intent.py, prompts.py, candidate_tools.py, hr_tools.py).

---

## 1. Stateless Retrieval Causes Miss on Follow-Up Comparisons

**ISSUE-01 — Stateless Retrieval Miss**

- **Risk Level:** HIGH
- **Description:** When HR asks to compare two candidates after a prior ranking turn, the system re-runs a full Qdrant search. The new query embedding differs from the ranking query, so the previously retrieved cvIds may not appear in the new top-N. The LLM correctly reports it cannot find them — because retrieval literally did not return them.
- **Example Trigger:** Turn 1: 'Liệt kê top 2 ứng viên phù hợp nhất' → Turn 2: 'So sánh chi tiết 2 ứng viên theo skill, kinh nghiệm, project'
- **Root Cause:** hr_graph.py → retrieve_hr_context_node: no intent branching. HRChatState TypedDict: no active_cv_ids field. save_hr_turn_node: does not persist cvIds discussed. load_hr_session_history_node: does not restore active_cv_ids.
- **Recommended Fix:** Add active_cv_ids: List[int] and hr_query_intent: str to HRChatState. Persist active_cv_ids (from cv_context) on every save. Restore on load. Implement COMPARE/DETAIL/RANK/GENERAL intent routing. Route COMPARE/DETAIL to a pinned-cvId direct fetch bypassing full reranking.

---

## 2. JD Context Injected Into Comparison Queries Unnecessarily

**ISSUE-02 — Unnecessary JD Injection**

- **Risk Level:** HIGH
- **Description:** retrieve_for_hr_mode_hr always fetches JD chunks and uses them as a ranking vector, even for queries like 'compare candidate A and B' where no JD reference is needed. JD content is injected into every LLM prompt, inflating token usage and adding irrelevant context.
- **Example Trigger:** 'Chi tiết về ứng viên Nguyen Van A' │ 'So sánh Pham Minh Chi và Nguyen Van A về kỹ năng backend'
- **Root Cause:** retriever.py → retrieve_for_hr_mode_hr: unconditionally fetches jd_chunks_for_vector. hr_graph.py → build_hr_prompts_node: calls _format_jd_context() on every turn regardless of content.
- **Recommended Fix:** In _fetch_pinned_cv_context (new helper for COMPARE/DETAIL), return jd_context=[]. In build_hr_prompts_node, only render JD section when state['jd_context'] is non-empty.

---

## 3. Apply Intent With Empty Scoring Cache — Silent Failure

**ISSUE-03 — Apply With No Score Cache**

- **Risk Level:** HIGH
- **Description:** Tầng-1 apply-intent fires and overrides intent to jd_search. If scored_jobs is empty (fresh session or cleared cache), finalize_application resolves pos_id from an empty list. pos_id remains None. The tool call either fails silently or throws a generic error with no user feedback.
- **Example Trigger:** Fresh session: 'Tôi muốn nộp đơn vào Java Intern' (first message, no prior jd_search turn has run)
- **Root Cause:** candidate_graph.py → llm_reasoning_node: finalize_application branch resolves pos_id from scored_jobs — no guard when scored_jobs is None/empty. No null-check on pos_id before tool invocation.
- **Recommended Fix:** Before finalize_application invocation: check scored_jobs is populated. If empty, return structured message: 'Bạn chưa xem đánh giá độ phù hợp. Hãy hỏi tôi tìm việc phù hợp trước để tôi có thể hỗ trợ bạn nộp đơn chính xác.'

---

## 4. Intent Classifier — jd_analysis vs jd_search Overlap

**ISSUE-04 — Classifier Intent Overlap**

- **Risk Level:** HIGH
- **Description:** jd_search and jd_analysis pattern sets share significant vocabulary (position, role, level, experience, requirement). Analysis questions trigger the expensive scoring pipeline; search questions may route to chunk-only retrieval. Either mismatch produces wrong results.
- **Example Trigger:** 'Vị trí này yêu cầu bao nhiêu năm kinh nghiệm?' │ 'Tech stack của vị trí Java Intern này là gì?'
- **Root Cause:** intent.py → _classify_career_intent: overlapping pattern vocabulary. _semantic_fallback boosts both intents on similar heuristics. 'This position' context not weighted strongly enough.
- **Recommended Fix:** Add high-weight disambiguation: 'this position / vị trí này / vị trí đó' → force jd_analysis when jd_id available. Add jd_search penalty when no first-person pronoun present. Add test coverage using hr_question.md and candidate_question.md as test cases.

---

## 5. Email Confirmation Race Condition — Double Send Risk

**ISSUE-05 — Email Confirmation Race**

- **Risk Level:** HIGH
- **Description:** pending_emails is stored in the last ASSISTANT turn's functionCall JSON. On rapid confirmation clicks or UI retries, a second request may load pending_emails before the first response is persisted. send_interview_email is then executed twice for the same candidate.
- **Example Trigger:** HR: 'Gửi email mời phỏng vấn cho Nguyen Van A' → [Bot asks confirm] → HR double-clicks 'Đồng ý'
- **Root Cause:** hr_graph.py → load_hr_session_history_node: loads pending_emails from DB without idempotency check. save_hr_turn_node: clears pending_emails in memory but DB write is async — a race window exists.
- **Recommended Fix:** Add action_id: UUID to pending_emails payload on creation. Before executing email send, check action_id not already present in last N ASSISTANT turns. Alternatively: store pending_emails in Redis with TTL=5min to avoid DB race entirely.

---

## 6. Candidate Name Resolution Too Broad

**ISSUE-06 — Broad Name Substring Match**

- **Risk Level:** MEDIUM
- **Description:** _resolve_candidates_by_name uses simple substring match. Searching 'Minh' matches all candidates whose name contains 'Minh', causing unnecessary disambiguation even when HR provides a complete, unambiguous full name.
- **Example Trigger:** 'Gửi email từ chối cho Pham Minh Chi' → matches Pham Minh Chi, Nguyen Minh Duc, Le Thi Minh Anh → disambiguation shown despite exact name given
- **Root Cause:** hr_graph.py → _resolve_candidates_by_name: 'query_lower in candidate_name.lower()' — substring-only, no exact-match priority.
- **Recommended Fix:** First attempt exact match (case-insensitive full name). If exactly 1 result → auto-resolve. Only fall through to substring if exact match yields 0 results. Document this behavior in code.

---

## 7. Hallucinated position_id in check_application_status

**ISSUE-07 — Hallucinated position_id**

- **Risk Level:** MEDIUM
- **Description:** When check_application_status tool is called, position_id is whatever the LLM chose to pass. It may be a hallucinated integer not in active_position_ids, causing the API to return empty data. The LLM then incorrectly reports the candidate has not applied.
- **Example Trigger:** 'Tôi đã nộp đơn vào Java Intern chưa?' → LLM passes position_id=99 (hallucinated) → API returns empty → bot says 'chưa nộp đơn'
- **Root Cause:** candidate_graph.py → llm_reasoning_node: check_application_status block — tool_args used as-is. position_id not validated against state['active_position_ids'].
- **Recommended Fix:** Validate tool_args['position_id'] against state['active_position_ids'] before invoking. If invalid or not in active list, default to position_id=None to fetch all applications (safer behavior).

---

## 8. Unlimited Chunks per cvId Inflate LLM Context

**ISSUE-08 — Unlimited Chunks per CV**

- **Risk Level:** MEDIUM
- **Description:** rerank_and_group returns ALL chunks for each top-N cvId without any per-CV limit. For 5 CVs averaging 6 chunks each, the LLM receives 30 chunks for a query asking for 'top 2'. This inflates prompt size 3-5x, increases latency, and raises inference cost.
- **Example Trigger:** Any HR ranking query. Log: '5 unique IDs → top 5 IDs selected (30 total chunks)' for a 2-candidate query
- **Root Cause:** reranker.py → rerank_and_group: after selecting top_n IDs, selected.extend(cv_chunks) without limit. No max_chunks_per_id parameter exists.
- **Recommended Fix:** Add max_chunks_per_id=6 parameter to rerank_and_group. After sorting chunks within each CV by reranker_score descending, slice to max_chunks_per_id before extending selected.

---

## 9. Wrong Payload Field Name in HR Scoring Node

**ISSUE-09 — Wrong chunkText Field**

- **Risk Level:** HIGH
- **Description:** hr_scoring_node reads CV content using payload.get('text','') but the actual Qdrant payload field is chunkText. Every candidate's CV text is an empty string. The scoring LLM receives '(CV content not available)' for every candidate and produces meaningless or random scores.
- **Example Trigger:** HR: 'Hãy đánh giá và chấm điểm các ứng viên này' → all candidates receive 0/random scores with no real content evaluation
- **Root Cause:** hr_graph.py → hr_scoring_node: c.get('payload',{}).get('text','') — field is 'chunkText' not 'text'. Also: retriever.py → retrieve_for_hr_mode_hr uses same wrong field for jd_text_for_search.
- **Recommended Fix:** Change all payload.get('text','') to payload.get('chunkText','') in hr_scoring_node and retrieve_for_hr_mode_hr. Add helper function get_chunk_text(payload: dict) -> str to centralize this access.

---

## 10. finalize_application skill_match/miss Type Mismatch

**ISSUE-10 — List vs String Type Mismatch**

- **Risk Level:** MEDIUM
- **Description:** scoring_node produces skillMatch and skillMiss as List[str]. The finalize_application tool signature expects plain str. The LLM may serialize the list as a Python repr string — '["Java", "Spring Boot"]' — causing malformed payloads sent to the Java API.
- **Example Trigger:** 'Tôi muốn nộp đơn' after scoring → tool called with skill_match='["Java", "Spring Boot"]' instead of 'Java, Spring Boot'
- **Root Cause:** candidate_graph.py → llm_reasoning_node: tool_args passed as-is from LLM without normalization. candidate_tools.py → finalize_application: accepts str but performs no input normalization.
- **Recommended Fix:** In llm_reasoning_node before finalize_application invocation: for k in ['skill_match','skill_miss']: tool_args[k] = ', '.join(tool_args[k]) if isinstance(tool_args.get(k), list) else tool_args.get(k, '')

---

## 11. Scoring Cache Lost After 20-Turn Sliding Window

**ISSUE-11 — Cache Lost in Long Sessions**

- **Risk Level:** MEDIUM
- **Description:** scored_jobs is cached in the last ASSISTANT turn's functionCall JSON. The sliding window fetches only MAX_HISTORY_TURNS rows. If the session has accumulated >N turns since scoring, the cache miss causes re-scoring (expensive) or scoring is skipped entirely.
- **Example Trigger:** Power user in a long session: scores set in turn 3, user now on turn 25 — cache not found in truncated history
- **Root Cause:** candidate_graph.py → load_session_history_node: iterates reversed(history) but history is already truncated. recruitment_api.py → get_history: fetches only limit rows without searching for last functionCall.
- **Recommended Fix:** Add API endpoint GET /internal/chatbot/session/{id}/last-function-call that queries without row limit for the last non-null functionCall ASSISTANT turn. Call this in load_session_history_node to restore scored_jobs regardless of sliding window.

---

## 12. Year Numbers Parsed as top_n

**ISSUE-12 — Year Numbers as top_n**

- **Risk Level:** LOW
- **Description:** _extract_top_n_from_query regex matches any standalone integer. 'Tìm ứng viên tốt nghiệp năm 2022' extracts top_n=2022. The guard cap at 50 prevents runaway fetches, but the intent is clearly wrong.
- **Example Trigger:** 'Tìm ứng viên có kinh nghiệm từ năm 2020' │ 'Ứng viên tốt nghiệp 2022 có không?'
- **Root Cause:** hr_graph.py → _extract_top_n_from_query: r'\b(?:top\s*)?(\d+)\b' matches any number without context. No requirement for surrounding quantity words.
- **Recommended Fix:** Require adjacent context: only match numbers ≤ 50 preceded by 'top', 'N người', 'N ứng viên', or followed by a candidate-count noun. Or add a minimum-context requirement to the regex.

---

## 13. Missing Status-Check Intent in Candidate Classifier

**ISSUE-13 — Missing Status-Check Intent**

- **Risk Level:** LOW
- **Description:** The intent classifier has no pattern for application status queries. These are classified as general/high-confidence and routed to skip_retrieve, meaning the LLM receives no retrieval context and cannot trigger check_application_status.
- **Example Trigger:** 'Tôi đã nộp đơn thành công chưa?' │ 'Trạng thái ứng tuyển của tôi là gì?' │ 'Đơn của tôi thế nào?'
- **Root Cause:** intent.py → _INTENT_PATTERNS: no status-check pattern. candidate_graph.py → should_retrieve: general+high-confidence routes to skip_retrieve → build_prompts without tool context.
- **Recommended Fix:** Add jd_search patterns: r'\b(đã nộp│applied│application status│trạng thái ứng tuyển│đơn của tôi)\b'. Or: add Tầng-1 hard-rule similar to _is_apply_intent for status-check intent, routing to jd_search so the check_application_status tool is reachable.

---

# PART 2 — Implementation Plan

Issues are organized into 4 phases by criticality. Phase 1 contains data-correctness bugs that silently corrupt results. Later phases address resilience and UX quality.

---

## PHASE 1 — Critical Bug Fixes — Week 1

**1.1 — ISSUE-09**
- **Effort:** 30 min
- **Change:** Fix payload.get('text') → payload.get('chunkText') in hr_scoring_node and retrieve_for_hr_mode_hr. Add get_chunk_text() helper.
- **Impact:** HR scoring produces real scores

**1.2 — ISSUE-01**
- **Effort:** 4 h
- **Change:** Add active_cv_ids + hr_query_intent to HRChatState. Implement detect_hr_query_intent(). Add _fetch_pinned_cv_context(). Route COMPARE/DETAIL to pinned fetch. Persist + restore active_cv_ids.
- **Impact:** Eliminates follow-up miss bug

**1.3 — ISSUE-02**
- **Effort:** 1 h
- **Change:** Return jd_context=[] from pinned fetch. Guard JD section render in build_hr_prompts_node when jd_context is empty.
- **Impact:** Leaner prompts, faster turns

**1.4 — ISSUE-10**
- **Effort:** 30 min
- **Change:** Normalize skill_match/skill_miss list→str in candidate_graph.py before finalize_application invocation.
- **Impact:** Correct Java API payloads

> **Estimated Impact: Scoring works correctly. Follow-up retrieval miss eliminated. API payloads correct.**

---

## PHASE 2 — High Impact Improvements — Week 2

**2.1 — ISSUE-08**
- **Effort:** 1 h
- **Change:** Add max_chunks_per_id=6 param to rerank_and_group. Slice chunks per CV after sorting by reranker_score descending.
- **Impact:** -40-60% LLM prompt size

**2.2 — ISSUE-03**
- **Effort:** 2 h
- **Change:** In llm_reasoning_node apply branch: if scored_jobs is empty, return structured guidance message instead of attempting tool call.
- **Impact:** No silent apply failures

**2.3 — ISSUE-07**
- **Effort:** 30 min
- **Change:** Validate position_id in check_application_status against active_position_ids. Default to None if invalid or hallucinated.
- **Impact:** Correct status responses

> **Estimated Impact: Prompt size -40-60%. No silent apply failures. Correct application status responses.**

---

## PHASE 3 — Reliability & UX Improvements — Week 3

**3.1 — ISSUE-04**
- **Effort:** 2 h
- **Change:** Add disambiguation rule: 'vị trí này' → force jd_analysis. Add jd_search penalty for no first-person pronoun. Add test coverage from .md files.
- **Impact:** -30% misrouted queries

**3.2 — ISSUE-06**
- **Effort:** 1 h
- **Change:** Exact match first in _resolve_candidates_by_name. Auto-resolve if 1 result. Substring only if 0 exact matches.
- **Impact:** Better email HR UX

**3.3 — ISSUE-13**
- **Effort:** 1 h
- **Change:** Add status-check patterns to jd_search in intent.py. Or: add Tầng-1 hard-rule similar to _is_apply_intent for status-check intent.
- **Impact:** Status tool reachable

> **Estimated Impact: Intent routing -30% error rate. Email UX improved. Status-check tool reachable.**

---

## PHASE 4 — Resilience & Scalability — Week 4

**4.1 — ISSUE-05**
- **Effort:** 3 h
- **Change:** Add action_id UUID to pending_emails. Dedup before email send by checking action_id in last N ASSISTANT turns. Consider Redis TTL alternative.
- **Impact:** No duplicate emails

**4.2 — ISSUE-11**
- **Effort:** 4 h
- **Change:** Add GET /internal/chatbot/session/{id}/last-function-call endpoint (Java). Call in load_session_history_node to restore scored_jobs regardless of sliding window.
- **Impact:** Score persistence

**4.3 — ISSUE-12**
- **Effort:** 30 min
- **Change:** Require adjacent quantity word context in _extract_top_n_from_query. Reject standalone year numbers.
- **Impact:** No oversized Qdrant fetches

> **Estimated Impact: No duplicate emails. Score persistence across long sessions. No oversized Qdrant fetches.**

---

# Summary — All 13 Issues

| Issue | Risk | Description | Phase | Effort | Impact After Fix |
|---|---|---|---|---|---|
| ISSUE-09 | HIGH | Wrong payload field 'text' vs 'chunkText' in scoring node | Phase 1 | 30min | Scoring works correctly |
| ISSUE-01 | HIGH | Stateless retrieval — no active_cv_ids tracking across turns | Phase 1 | 4h | Eliminates follow-up miss bug |
| ISSUE-02 | HIGH | JD context injected into compare/detail queries unnecessarily | Phase 1 | 1h | Cleaner prompts, -30% latency |
| ISSUE-10 | MEDIUM | skill_match list vs string type mismatch in finalize_application | Phase 1 | 30min | Correct API payloads |
| ISSUE-08 | MEDIUM | Unlimited chunks per cvId inflate LLM context | Phase 2 | 1h | -40-60% prompt size |
| ISSUE-03 | HIGH | Apply intent with empty scoring cache causes silent failure | Phase 2 | 2h | No silent apply failures |
| ISSUE-07 | MEDIUM | Hallucinated position_id in check_application_status | Phase 2 | 30min | Correct status responses |
| ISSUE-04 | HIGH | jd_analysis vs jd_search intent classifier overlap | Phase 3 | 2h | -30% misrouted queries |
| ISSUE-06 | MEDIUM | Overly broad candidate name substring matching | Phase 3 | 1h | Better email UX |
| ISSUE-13 | LOW | Missing status-check intent patterns in classifier | Phase 3 | 1h | Status tool reachable |
| ISSUE-05 | HIGH | Email confirmation race condition — double send risk | Phase 4 | 3h | No duplicate emails |
| ISSUE-11 | MEDIUM | Scoring cache lost when session > 20 turns | Phase 4 | 4h | Score persistence |
| ISSUE-12 | LOW | Year numbers parsed as top_n in HR query extraction | Phase 4 | 30min | No oversized Qdrant fetches |

*Total estimated effort: ~26 hours across 4 weeks. Phase 1 (6 hours) alone eliminates the three highest-impact silent failures and should be the immediate priority.*