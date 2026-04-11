# CHATBOT_SYSTEM_CONTEXT.md
> **Source of Truth** — Tài liệu này là nguồn tham chiếu duy nhất cho mọi AI Agent và Developer làm việc trên feature Chatbot của hệ thống CVReview.
> Last updated: 2026-04-11

---

## 🗺️ Tổng quan hệ thống

Hệ thống tuyển dụng nội bộ (Internal) tích hợp AI với **2 chatbot riêng biệt**:
- **Candidate Chatbot**: Giúp ứng viên tìm việc phù hợp và nộp đơn ứng tuyển.
- **HR Chatbot**: Giúp HR sàng lọc ứng viên với 2 mode riêng biệt.

**Stack kỹ thuật:**
- Backend Java: `recruitment-service` (Spring Boot) — quản lý dữ liệu, business logic, email.
- Backend Python: `chatbot-service` (FastAPI + LangGraph) — AI reasoning, RAG pipeline.
- Vector DB: **Qdrant** — lưu embeddings CV & JD.
- LLM: **Gemini 2.5 Flash** — Scoring & Chat.
- Embedding Model: **BAAI/bge-small-en-v1.5** (384 dims).

---

## 🏗️ Kiến trúc dữ liệu (Source of Truth)

### 1. Mô hình CandidateCV (3 loại bản ghi)

```
[Master CV]  candidateId=X, positionId=NULL, sourceType=CANDIDATE, parentCvId=NULL
                  │ (được embed vào Qdrant, candidateId trong payload)
                  │
[Application CV]  candidateId=X, positionId=5, sourceType=CANDIDATE, parentCvId=<masterId>
                  │ (KHÔNG embed lại Qdrant — dùng lại vector của Master)
                  └── CVAnalysis (score, feedback, skillMatch, skillMiss)

[HR CV]      candidateId=NULL, positionId=5, sourceType=HR, parentCvId=NULL
                  │ (được embed vào Qdrant, positionId trong payload)
                  └── CVAnalysis (score từ AI batch scoring của ai-service)
```

**Quy tắc bất biến:**
- Một Candidate chỉ có **đúng 1 Master CV** (positionId IS NULL).
- Candidate có thể có **nhiều Application CVs** (1 per position applied).
- Application CV **không bao giờ được embed vào Qdrant** — kế thừa vectors của Master.
- `parent_cv_id` trên Application CV → FK về Master CV (dùng cho GC và cascade delete).
- Khi Candidate upload CV mới: **soft-delete** tất cả Application CVs cũ (và CVAnalysis tương ứng) → Candidate phải nộp lại.

### 2. Positions — Recruitment Period

Mỗi đợt tuyển dụng = **1 row trong bảng `positions`** (không có bảng Recruitment riêng).

**Recruitment Period display** = `name + language + level + opened_at`
Ví dụ: `"Dev Java Intern — 20/04/2025"`

Fields cần thêm vào entity:
| Field | Type | Default | Ý nghĩa |
|-------|------|---------|---------|
| `is_active` | BOOLEAN | TRUE | Position đang trong đợt tuyển |
| `opened_at` | DATETIME | NOW() | Ngày mở đợt tuyển |
| `closed_at` | DATETIME | NULL | Ngày đóng (null = đang mở) |

### 3. CVAnalysis — Quan hệ

- `CVAnalysis` giữ quan hệ `@OneToOne` với `CandidateCV`.
- Bây giờ Application CV mới có `positionId` → `CVAnalysis` linked tới Application CV là đúng.
- **Không cần đổi @OneToOne thành @OneToMany** — model mới tự nhiên giải quyết.

### 4. Chat History

Lưu tại MySQL của `recruitment-service`. FE chỉ call API để đọc.

**Bảng `chat_session`:**
| Field | Type | Ghi chú |
|-------|------|---------|
| `session_id` | VARCHAR(36) PK | UUID, do BE tạo khi "New Chat" |
| `user_id` | VARCHAR(36) | HR hoặc Candidate ID |
| `chatbot_type` | ENUM('HR','CANDIDATE') | |
| `position_id` | INT nullable | Context của HR chatbot |
| `mode` | ENUM('HR_MODE','CANDIDATE_MODE') nullable | Chỉ cho HR chatbot |
| `created_at` | DATETIME | |
| `last_active_at` | DATETIME | Cập nhật mỗi turn |

**Bảng `chat_history`** (mỗi turn = 1 row):
| Field | Type | Ghi chú |
|-------|------|---------|
| `id` | BIGINT PK | |
| `session_id` | VARCHAR(36) INDEX | FK → chat_session |
| `role` | ENUM('USER','ASSISTANT') | |
| `content` | TEXT | Nội dung message |
| `function_call` | JSON nullable | Log function calls (finalize_application, send_email...) |
| `created_at` | DATETIME | |

**Session lifecycle:**
- BE tạo session khi FE gọi `POST /chatbot/session`.
- FE đính kèm `session_id` vào mọi request message.
- **Sliding Window**: BE query 20 rows gần nhất theo session_id để build LLM context (session có thể dài hơn 20 turns, nhưng LLM chỉ thấy 20 gần nhất).

---

## 🔄 Luồng xử lý chi tiết

### Luồng 1: Candidate Chatbot

**Scope Qdrant:** Chỉ query JDs có `positionId IN [active_position_ids]`.

**Bước lấy active positions (always-fresh):**
```
Mỗi request → chatbot-service gọi GET /internal/positions/active
→ Returns: [{id, name, language, level}]  (small dataset, < 50 rows, cực nhanh)
→ Filter Qdrant: jdId IN [active_ids] + is_latest=True
```

**Intent scoring (Option A — Batch):**
```
User: "Tìm việc phù hợp với tôi"
  → 1. Embed query + CV content
  → 2. Qdrant search JD collection (filter is_active) → top-10 JDs
  → 3. Batch gửi [CV_text + 10 JD_texts] cho Gemini
  → 4. Gemini trả về [{positionId, score, feedback, skillMatch, skillMiss}]
  → 5. Cache scores trong session (lưu vào function_call JSON của last message)
  → 6. Present ranked list cho user
```

**Guardrail khi nộp đơn:**
```
User: "Tôi muốn nộp đơn vào Java Intern"
  → Check score >= 70 (từ session cache)
  → Nếu < 70: từ chối, giải thích skill gaps, gợi ý lộ trình học
  → Nếu >= 70: trigger function call finalize_application(...)
```

**Function: `finalize_application`**
```
Chatbot-service gọi: POST /internal/finalize-application
Body: { candidateId, positionId, score, feedback, skillMatch, skillMiss, sessionId }

Recruitment-service thực hiện:
  1. Tìm Master CV (candidateId=X, positionId IS NULL, deletedAt IS NULL)
  2. Copy → tạo Application CV (positionId=Y, parentCvId=masterId, sourceType=CANDIDATE)
  3. INSERT CVAnalysis (score/feedback/skillMatch/skillMiss từ chatbot)
  4. KHÔNG đụng Qdrant
  → Response: { applicationCvId, message: "Application submitted" }
```

### Luồng 2: HR Chatbot — Mode HR

**Scope Qdrant:** Filter theo `positionId=X AND sourceType='HR' AND is_latest=True`.

```
HR vào giao diện: chọn Recruitment (Dev Java Intern 20-04-2025) → Mode HR
  → Session được tạo với {positionId=5, mode=HR_MODE}
  → Chatbot chỉ query CVs thuộc positionId=5 và sourceType=HR
  
HR hỏi: "Cho tôi top 5 ứng viên Java phù hợp nhất"
  → Qdrant filter: positionId=5, sourceType=HR
  → Fetch CV chunks → Gemini rank và explain
  → Combine với score/feedback từ CVAnalysis (qua SQL API)
```

### Luồng 3: HR Chatbot — Mode Candidate

**Scope Qdrant:** Lấy `candidateId` list từ SQL, filter Qdrant theo `candidateId`.
Lý do: Master CV được embed với candidateId (không có positionId), Application CV không embed.

```
HR chọn Mode Candidate (vẫn với positionId=5)
  → Session context: {positionId=5, mode=CANDIDATE_MODE}

Step 1: Query SQL:
  GET /internal/applications?positionId=5
  → [{candidateId: "uuid-A", appCvId: 10, score: 90, feedback: "..."}, ...]

Step 2: Extract candidateIds = ["uuid-A", "uuid-B", ...]

Step 3: Filter Qdrant:
  candidateId IN ["uuid-A", "uuid-B", ...]  (MatchAny)
  + is_latest=True
  + sourceType=CANDIDATE (để không lẫn HR CVs)
  → Fetch CV chunks của Master CVs

Step 4: Combine vectors + scores từ SQL → Gemini reasoning → Response
```

**Lưu ý scale:** 200-300 CVs candidate → `MatchAny` filter trên Qdrant hoàn toàn xử lý được.

### Luồng 4: HR gửi email phỏng vấn

```
HR: "Gửi email phỏng vấn cho ứng viên Nguyễn Văn A vào 15/04/2025 lúc 9h"
  → chatbot nhận diện intent: send_interview_email
  → function call: send_interview_email({candidateId, candidateName, candidateEmail, 
                                          positionId, positionName, interviewDate, 
                                          customMessage, sessionId})
  
Chatbot-service gọi: POST /internal/notify/interview
  → Recruitment-service gửi email thật qua SMTP (Spring Mail)
  → Log function_call trong chat_history row
```

---

## 🔁 Qdrant Filter Strategy

### Candidate Chatbot
```python
# JD search (active positions only)
active_ids = await get_active_position_ids()  # call SQL API
jd_filter = Filter(must=[
    FieldCondition(key="jdId", match=MatchAny(any=active_ids)),
    FieldCondition(key="is_latest", match=MatchValue(value=True))
])

# CV retrieval (candidate's own master CV)
cv_filter = Filter(must=[
    FieldCondition(key="candidateId", match=MatchValue(value=candidate_id)),
    FieldCondition(key="is_latest", match=MatchValue(value=True))
])
```

### HR Chatbot — Mode HR
```python
cv_filter = Filter(must=[
    FieldCondition(key="positionId", match=MatchValue(value=position_id)),
    FieldCondition(key="sourceType", match=MatchValue(value="HR")),
    FieldCondition(key="is_latest", match=MatchValue(value=True))
])
```

### HR Chatbot — Mode Candidate
```python
# Step 1: get candidateIds from SQL
applications = await recruitment_api.get_applications(position_id)
candidate_ids = [app["candidateId"] for app in applications]

# Step 2: filter Qdrant by candidateId (master CV vectors)
cv_filter = Filter(must=[
    FieldCondition(key="candidateId", match=MatchAny(any=candidate_ids)),
    FieldCondition(key="sourceType", match=MatchValue(value="CANDIDATE")),
    FieldCondition(key="is_latest", match=MatchValue(value=True))
])
```

---

## 🛠️ API Contract

### Internal APIs (Recruitment Service → Chatbot Service)

| Method | Endpoint | Caller | Mô tả |
|--------|----------|--------|-------|
| `POST` | `/internal/chatbot/session` | chatbot-service | Tạo session mới |
| `GET` | `/internal/chatbot/session/{sessionId}/history?limit=20` | chatbot-service | Lấy 20 messages gần nhất |
| `POST` | `/internal/chatbot/message` | chatbot-service | Lưu 1 message turn |
| `POST` | `/internal/chatbot/finalize-application` | chatbot-service | Tạo Application CV + CVAnalysis |
| `GET` | `/internal/chatbot/positions/active` | chatbot-service | Lấy danh sách active position IDs |
| `GET` | `/internal/chatbot/applications?positionId=X` | chatbot-service | Lấy candidateIds ứng tuyển theo position |
| `POST` | `/internal/chatbot/notify/interview` | chatbot-service | Gửi email phỏng vấn SMTP |

### Public APIs (FE → Recruitment Service, qua API Gateway)

| Method | Endpoint | Role | Mô tả |
|--------|----------|------|-------|
| `GET` | `/api/chatbot/sessions` | HR/CANDIDATE | Danh sách sessions của user |
| `GET` | `/api/chatbot/sessions/{sessionId}` | HR/CANDIDATE | Full chat history của 1 session |

### Chatbot Service Endpoints (FE → chatbot-service, qua API Gateway)

| Method | Endpoint | Role | Mô tả |
|--------|----------|------|-------|
| `POST` | `/chatbot/candidate/session` | CANDIDATE | Khởi tạo session Candidate chatbot |
| `POST` | `/chatbot/candidate/chat` | CANDIDATE | Gửi message Candidate chatbot |
| `POST` | `/chatbot/hr/session` | HR | Khởi tạo session HR chatbot |
| `POST` | `/chatbot/hr/chat` | HR | Gửi message HR chatbot |

---

## 🤖 Function Calling Tools

### Candidate Chatbot Tools
```python
CANDIDATE_TOOLS = [
    {
        "name": "evaluate_cv_fit",
        "description": "Tính điểm phù hợp của CV với một hoặc nhiều JDs. Gọi khi user hỏi về độ phù hợp hoặc tìm việc.",
        "parameters": {
            "position_ids": "list[int] — danh sách positionId cần chấm điểm (tối đa 10)"
        }
    },
    {
        "name": "finalize_application",
        "description": "Nộp đơn ứng tuyển chính thức. CHỈ được gọi khi score >= 70.",
        "parameters": {
            "position_id": "int",
            "score": "int — điểm đã được evaluate_cv_fit tính",
            "feedback": "str",
            "skill_match": "str",
            "skill_miss": "str"
        }
    }
]
```

### HR Chatbot Tools
```python
HR_TOOLS = [
    {
        "name": "get_candidate_details",
        "description": "Lấy thông tin chi tiết score/feedback của 1 ứng viên cụ thể từ SQL.",
        "parameters": {
            "candidate_id": "str"
        }
    },
    {
        "name": "send_interview_email",
        "description": "Gửi email mời phỏng vấn hoặc thông báo trúng tuyển cho ứng viên qua SMTP.",
        "parameters": {
            "candidate_id": "str",
            "candidate_email": "str",
            "candidate_name": "str",
            "email_type": "ENUM('INTERVIEW_INVITE', 'OFFER_LETTER', 'REJECTION')",
            "interview_date": "str — ISO format, nullable",
            "custom_message": "str"
        }
    }
]
```

---

## ⚙️ Scoring Logic (Candidate Chatbot)

```
Trigger: User hỏi về job phù hợp hoặc muốn phân tích độ phù hợp

1. Lấy active position IDs từ SQL
2. Qdrant search JD collection (filter is_active) → top-10 JDs gần nhất với CV
3. Gemini nhận: [CV_full_text + 10 JD_texts]
4. Prompt: "Score từng JD từ 0-100 dựa trên CV. Trả về JSON array."
5. Cache kết quả vào session (lưu trong function_call JSON)

Guardrail:
- score < 70  → Từ chối apply, explain skill gaps, gợi ý học tập
- score >= 70 → Cho phép gọi finalize_application
```

---

## 🔒 Bảo mật Internal APIs

Tất cả `/internal/*` endpoints phải:
1. **Không** đi qua API Gateway public route.
2. Chỉ nhận traffic từ `chatbot-service` (Docker network internal).
3. Validate header `X-Internal-Service: chatbot-service` (shared secret).

---

## ⚠️ Business Rules quan trọng

1. **Candidate chỉ có 1 Master CV**: Check `positionId IS NULL` trước khi throw DUPLICATE_CV.
2. **Re-upload CV**: Soft-delete tất cả Application CVs cũ (set `deleted_at=NOW()`). CVAnalysis cascade soft-delete. Candidate phải nộp lại từ đầu.
3. **Guardrail bất biến**: `finalize_application` KHÔNG được gọi nếu `score < 70`, kể cả khi user cố bypass.
4. **is_active sync**: Không lưu `is_active` vào Qdrant. Thay vào đó, chatbot luôn query SQL để lấy active position IDs trước mỗi JD search.
5. **Application CV không embed**: Khi HR chatbot mode CANDIDATE query, luôn filter Qdrant bằng `candidateId` (of master CV), không bao giờ bằng `cvId` của Application CV.