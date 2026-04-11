# Chatbot Feature — Implementation Plan

Xây dựng hệ thống Chatbot dual-mode (Candidate + HR) cho hệ thống CVReview, tích hợp LangGraph, Qdrant RAG, Gemini function calling, và email SMTP.

## Proposed Changes

---

### Phase 1 — Database & Entity (recruitment-service / Java)

> Mục tiêu: Chuẩn bị data layer để hỗ trợ toàn bộ chatbot logic

---

#### [MODIFY] [Positions.java](file:///d:/CVReview/BackEnd/recruitment-service/src/main/java/org/example/recruitmentservice/models/entity/Positions.java)

Thêm 3 fields mới cho recruitment period:
- `is_active` BOOLEAN (default = TRUE)
- `opened_at` DATETIME
- `closed_at` DATETIME (nullable — null = đang mở)

---

#### [MODIFY] [CandidateCV.java](file:///d:/CVReview/BackEnd/recruitment-service/src/main/java/org/example/recruitmentservice/models/entity/CandidateCV.java)

- Thêm field `parentCvId` INT nullable (FK tự tham chiếu — Master CV ID).
- **Xóa** UNIQUE constraint trên `candidateId` ở DB migration.
- Giữ nguyên quan hệ `@ManyToOne` với `Position` (đã có).

---

#### [MODIFY] [UploadCVService.java](file:///d:/CVReview/BackEnd/recruitment-service/src/main/java/org/example/recruitmentservice/services/UploadCVService.java)

- Sửa logic check duplicate: chỉ check `candidateId` với `positionId IS NULL` (Master CV).
- Thêm logic **re-upload**: khi Master CV đã tồn tại, soft-delete Master + tất cả Application CVs (`parentCvId = oldMasterId`) trước khi upload mới.

---

#### [NEW] ChatSession.java

Entity mới cho bảng `chat_session`:
```
session_id (PK, VARCHAR 36)
user_id (VARCHAR 36)
chatbot_type (ENUM: HR, CANDIDATE)
position_id (INT nullable)
mode (ENUM: HR_MODE, CANDIDATE_MODE — nullable)
created_at (DATETIME)
last_active_at (DATETIME)
```

---

#### [NEW] ChatHistory.java

Entity mới cho bảng `chat_history` (mỗi turn = 1 row):
```
id (BIGINT PK, auto-increment)
session_id (VARCHAR 36, INDEX, FK → chat_session)
role (ENUM: USER, ASSISTANT)
content (TEXT)
function_call (JSON nullable — log tool calls)
created_at (DATETIME)
```

---

#### [NEW] ChatSessionRepository.java
#### [NEW] ChatHistoryRepository.java

Repository interfaces cho 2 entity mới.

---

### Phase 2 — Internal & Public APIs (recruitment-service / Java)

> Mục tiêu: Cung cấp đầy đủ API cho chatbot-service gọi vào

---

#### [NEW] ChatbotInternalController.java

Controller xử lý tất cả `/internal/chatbot/*` endpoints. Bảo vệ bằng header `X-Internal-Service: chatbot-service`.

**Endpoints:**

| Method | Path | Body / Params | Response |
|--------|------|---------------|----------|
| `POST` | `/internal/chatbot/session` | `{userId, chatbotType, positionId?, mode?}` | `{sessionId}` |
| `GET` | `/internal/chatbot/session/{sessionId}/history` | `?limit=20` | `[{role, content, functionCall, createdAt}]` |
| `POST` | `/internal/chatbot/message` | `{sessionId, role, content, functionCall?}` | `{id}` |
| `POST` | `/internal/chatbot/finalize-application` | `{candidateId, positionId, score, feedback, skillMatch, skillMiss, sessionId}` | `{applicationCvId}` |
| `GET` | `/internal/chatbot/positions/active` | — | `[{id, name, language, level, openedAt}]` |
| `GET` | `/internal/chatbot/applications` | `?positionId=X` | `[{candidateId, appCvId, score, feedback}]` |
| `POST` | `/internal/chatbot/notify/interview` | `{candidateId, candidateEmail, candidateName, positionId, positionName, emailType, interviewDate?, customMessage}` | `{success}` |

---

#### [NEW] ChatbotPublicController.java

Controller xử lý `/api/chatbot/*` cho FE (đi qua API Gateway, cần JWT).

| Method | Path | Role | Response |
|--------|------|------|----------|
| `GET` | `/api/chatbot/sessions` | HR/CANDIDATE | Danh sách sessions của user (paginated) |
| `GET` | `/api/chatbot/sessions/{sessionId}` | HR/CANDIDATE | Full history của session |

---

#### [NEW] ChatSessionService.java

Business logic cho session và history management:
- `createSession(...)` — tạo session mới, generate UUID.
- `getHistory(sessionId, limit)` — query 20 rows gần nhất.
- `saveMessage(sessionId, role, content, functionCall)` — persist 1 turn.
- `getUserSessions(userId, pageable)` — lấy sessions của user.

---

#### [NEW] FinalizeApplicationService.java

Business logic cho finalize application:
- Tìm Master CV theo `candidateId + positionId IS NULL`.
- Copy → tạo Application CV với `positionId`, `parentCvId`.
- INSERT `CVAnalysis` với score/feedback từ chatbot.
- Không tương tác với Qdrant.

---

#### [NEW] NotificationService.java (hoặc extend service hiện có nếu có)

- `sendInterviewEmail(...)` — gửi email SMTP qua Spring Mail.
- Hỗ trợ 3 loại: `INTERVIEW_INVITE`, `OFFER_LETTER`, `REJECTION`.
- Dùng email templates (HTML).

---

### Phase 3 — Candidate Chatbot (chatbot-service / Python)

> Mục tiêu: Refactor chatbot hiện tại, thêm scoring, function calling, session management

---

#### [NEW] app/services/recruitment_api.py

HTTP client (dùng `httpx`) để chatbot-service gọi recruitment-service:
- `create_session(user_id, chatbot_type, position_id, mode)`
- `get_history(session_id, limit=20)`
- `save_message(session_id, role, content, function_call=None)`
- `get_active_positions()` — cho JD filter
- `finalize_application(candidate_id, position_id, score, feedback, ...)`

---

#### [MODIFY] app/api/routes/chat.py → Tách thành 2 files:

**[NEW] app/api/routes/candidate_chat.py**

Route mới cho Candidate chatbot:
- `POST /chatbot/candidate/session` → tạo session (gọi recruitment-service internal).
- `POST /chatbot/candidate/chat` → gửi message, trigger LangGraph, save to history.

**[NEW] app/api/routes/hr_chat.py**

Route mới cho HR chatbot:
- `POST /chatbot/hr/session` → tạo session với `positionId` và `mode`.
- `POST /chatbot/hr/chat` → HR message pipeline.

---

#### [MODIFY] app/rag/graph.py → đổi tên thành `candidate_graph.py`

Refactor `CareerChatbot`/`ChatState`:
- Thêm `session_id`, `active_position_ids` vào `ChatState`.
- Thêm node `load_session_history` (query recruitment-service API trước khi classify intent).
- Thêm node `save_turn` (persist sau khi có LLM response).
- Thêm node `scoring_node` cho batch scoring Gemini (intent `jd_search`).

---

#### [NEW] app/rag/candidate_tools.py

Định nghĩa function tools cho Candidate chatbot:
- `evaluate_cv_fit(position_ids: list[int])` — batch scoring top-10 JDs.
- `finalize_application(position_id, score, feedback, skill_match, skill_miss)` — kiểm tra score >= 70 trước khi gọi API.

---

#### [MODIFY] app/services/retriever.py

Thêm method `retrieve_for_hr_mode_candidate`:
- Nhận `candidate_ids: list[str]`.
- Filter Qdrant `candidateId IN [candidate_ids]` + `sourceType=CANDIDATE` + `is_latest=True`.

Sửa `_retrieve_jd_search_with_cv` để nhận `active_jd_ids` filter thay vì luôn search toàn bộ JD collection.

---

#### [MODIFY] app/config.py

Thêm config:
```python
RECRUITMENT_SERVICE_URL: str = "http://recruitment-service:8080"
INTERNAL_SERVICE_SECRET: str = ""  # shared secret
MAX_HISTORY_TURNS: int = 20
SCORE_THRESHOLD: int = 70
```

---

### Phase 4 — HR Chatbot (chatbot-service / Python)

> Mục tiêu: Xây dựng LangGraph workflow mới cho HR, hỗ trợ 2 mode

---

#### [NEW] app/rag/hr_graph.py

LangGraph workflow riêng cho HR chatbot với `HRChatState`:
```python
class HRChatState(TypedDict):
    query: str
    hr_id: str
    session_id: str
    position_id: int
    mode: Literal["HR_MODE", "CANDIDATE_MODE"]
    candidate_ids: Optional[List[str]]   # chỉ CANDIDATE_MODE
    conversation_history: List[Dict]
    # ... intent, context, response fields
```

**Nodes:**
1. `load_hr_session_history` — lấy 20 turns gần nhất.
2. `classify_hr_intent` — HR-specific intents: `candidate_search`, `candidate_compare`, `candidate_detail`, `send_email`, `general`.
3. `load_candidate_scope` — nếu `CANDIDATE_MODE`: gọi SQL lấy candidateIds ứng tuyển.
4. `retrieve_hr_context` — Qdrant query theo mode (HR filter hoặc candidateId filter).
5. `load_sql_metadata` — fetch scores/feedback từ recruitment-service API cho candidates retrieved.
6. `build_hr_prompts` — build prompt kết hợp vector context + SQL metadata.
7. `llm_hr_reasoning` — Gemini gọi với HR tools.
8. `handle_tool_calls` — nếu có function call, execute tool (gọi API tương ứng).
9. `save_hr_turn` — persist message + function_call JSON.
10. `format_hr_response`.

---

#### [NEW] app/rag/hr_tools.py

```python
HR_TOOLS = [
    {
        "name": "get_candidate_details",
        "description": "Lấy score/feedback chi tiết của ứng viên từ database",
        "parameters": {"candidate_id": "str"}
    },
    {
        "name": "send_interview_email",
        "description": "Gửi email phỏng vấn hoặc offer letter cho ứng viên",
        "parameters": {
            "candidate_id": "str",
            "candidate_email": "str",
            "candidate_name": "str",
            "email_type": "INTERVIEW_INVITE | OFFER_LETTER | REJECTION",
            "interview_date": "str — ISO format, nullable",
            "custom_message": "str"
        }
    }
]
```

---

#### [MODIFY] app/main.py

Đăng ký 2 routers mới:
```python
app.include_router(candidate_chat.router, prefix="/chatbot")
app.include_router(hr_chat.router, prefix="/chatbot")
```
Giữ lại router cũ (hoặc deprecated nếu không dùng).

---

## Verification Plan

### Automated Tests

```bash
# Recruitment Service
mvn test -pl recruitment-service

# Chatbot Service  
cd chatbot-service
pytest app/test_chatbot.py -v
pytest app/test_retriever.py -v
```

### Manual Test Scenarios

| # | Scenario | Expected |
|---|----------|----------|
| 1 | Candidate upload CV → hỏi job phù hợp | Chatbot trả về danh sách jobs có score |
| 2 | Score < 70, Candidate yêu cầu apply | Chatbot từ chối, giải thích skill gap |
| 3 | Score >= 70, Candidate apply | `finalize_application` trigger, CV được copy vào DB |
| 4 | Candidate re-upload CV | Application CVs cũ bị soft-delete |
| 5 | HR Mode HR: hỏi top candidates | Filter đúng positionId + sourceType=HR |
| 6 | HR Mode Candidate: hỏi top candidates | SQL join → candidateId filter Qdrant |
| 7 | HR gửi email phỏng vấn qua chatbot | Email được gửi qua SMTP, logged trong chat_history |
| 8 | Candidate/HR xem lại chat history | GET /api/chatbot/sessions trả về đúng |
| 9 | Sliding window > 20 turns | LLM chỉ nhận 20 turns gần nhất |
| 10 | Position bị deactivate | Chatbot không còn gợi ý JD đó |

---

## Open Questions

> [!NOTE]
> Tất cả các câu hỏi dưới đây đã được giải quyết qua thảo luận. Ghi chú lại để tham chiếu.

- ✅ Hướng A vs B → **Hybrid**: Application CV copy nhưng không embed lại Qdrant, filter bằng candidateId.
- ✅ CVAnalysis → Giữ @OneToOne, linked tới Application CV (có positionId).
- ✅ Recruitment Period → Không có bảng riêng, display = fields trong Positions ghép lại.
- ✅ is_active sync → Always-fresh: query SQL mỗi request (không lưu vào Qdrant).
- ✅ Session → BE tạo UUID, FE đính kèm session_id vào mỗi request.
- ✅ History storage → Row per message trong bảng `chat_history`.
- ✅ Scoring timing → Option A (batch top-10 JDs khi user hỏi job).
- ✅ Re-upload CV → Soft-delete tất cả Application CVs cũ.
- ✅ HR email → Real SMTP email (Spring Mail), không chỉ in-system notification.
