# Chatbot Feature — Hướng dẫn Testing Toàn diện (Phase 0–3)

Tài liệu này hướng dẫn test toàn bộ feature Chatbot sau 4 phases. Hệ thống sử dụng cơ chế **Session-based**, tách biệt **Candidate Chatbot** và **HR Chatbot (2 modes)**.

Dùng **Postman / Insomnia / cURL**. Tất cả Python endpoints chạy trên port **8085**, Java (recruitment-service) trên port **8080**.

---

## 1. Candidate Chatbot

> Flow: Đăng nhập session → Hỏi về JD phù hợp (scoring) → Hỏi follow-up (cache hit) → Apply → Kiểm tra trạng thái.

### Bước 1.1 — Tạo Session

```http
POST http://localhost:8085/chatbot/candidate/session
Content-Type: application/json

{
  "user_id": "<UUID của Candidate>"
}
```

**Kết quả:** `{ "session_id": "..." }` — copy giá trị này.

---

### Bước 1.2 — Hỏi về công việc phù hợp (Turn 1 — scoring đầy đủ)

```http
POST http://localhost:8085/chatbot/candidate/chat
Content-Type: application/json

{
  "session_id": "<session_id>",
  "candidate_id": "<UUID của Candidate>",
  "query": "CV của tôi phù hợp với vị trí nào nhất? Cho tôi biết điểm phù hợp."
}
```

**Kết quả mong đợi:**
- `metadata.scored_jobs` có danh sách `[{positionId, score, skillMatch, skillMiss, feedback}]`
- Câu trả lời rank positions theo điểm giảm dần
- Log backend: `[Retriever] Mode A (no cache)` → `[Scoring] Scored N positions`

---

### Bước 1.3 — Follow-up về lương (Turn 2 — Dual-mode cache hit)

```http
POST http://localhost:8085/chatbot/candidate/chat
Content-Type: application/json

{
  "session_id": "<session_id>",
  "candidate_id": "<UUID của Candidate>",
  "query": "Lương của vị trí Java Intern là bao nhiêu?"
}
```

**Kết quả mong đợi:**
- Log backend: `[Retriever] Mode B (scoring cache hit)` — **KHÔNG** có Mode A
- Prompt template là `JD_BENEFITS_PROMPT` (sub-intent: benefits)
- Câu trả lời lấy salary CHÍNH XÁC từ JD section, không hallucinate

---

### Bước 1.4 — Hỏi về quy trình phỏng vấn (sub-intent: process)

```http
{
  "session_id": "<session_id>",
  "candidate_id": "<UUID của Candidate>",
  "query": "Quy trình phỏng vấn vị trí đó gồm mấy vòng?"
}
```

**Kết quả mong đợi:**
- Prompt template là `JD_PROCESS_PROMPT`
- Hệ thống liệt kê các vòng từ JD, nếu không có ghi "Not specified in JD"

---

### Bước 1.5 — Hỏi về kế hoạch học tập (sub-intent: improve)

```http
{
  "session_id": "<session_id>",
  "candidate_id": "<UUID của Candidate>",
  "query": "Tôi cần học thêm gì để đủ điều kiện vị trí Java Junior?"
}
```

**Kết quả mong đợi:**
- Prompt template là `CV_IMPROVE_PROMPT`
- Có bảng skill gap và roadmap 90 ngày với resource cụ thể (Udemy, LeetCode...)

---

### Bước 1.6 — Apply (score >= 70)

> Chỉ thực hiện khi `scored_jobs` tồn tại trong cache và ít nhất 1 vị trí đạt >= 70 điểm.

```http
POST http://localhost:8085/chatbot/candidate/chat
Content-Type: application/json

{
  "session_id": "<session_id>",
  "candidate_id": "<UUID của Candidate>",
  "query": "Tôi muốn nộp đơn vào vị trí Java Developer"
}
```

**Kết quả mong đợi:**
- Log: `[Tầng 1] Apply intent detected` → `finalize_application` tool được gọi
- `metadata.function_calls[0].name = "finalize_application"`
- Database: có row mới trong `candidate_cv` với `sourceType=CANDIDATE, positionId!=NULL`
- **Không** yêu cầu user nhập `candidateId` hay `score`

---

### Bước 1.7 — Apply bị từ chối (score < 70)

Thực hiện với candidate có score < 70:

```http
{
  "query": "Hãy giúp tôi apply vào vị trí Senior Architect"
}
```

**Kết quả mong đợi:**
- Bot từ chối với message rõ ràng về skill gaps
- `finalize_application` KHÔNG được gọi (kiểm tra `metadata.function_calls`)

---

### Bước 1.8 — Kiểm tra trạng thái ứng tuyển (Phase 3 — new tool)

```http
POST http://localhost:8085/chatbot/candidate/chat
Content-Type: application/json

{
  "session_id": "<session_id>",
  "candidate_id": "<UUID của Candidate>",
  "query": "Tôi đã nộp đơn vào vị trí nào rồi? Trạng thái thế nào?"
}
```

**Kết quả mong đợi:**
- `metadata.function_calls[0].name = "check_application_status"`
- Bot liệt kê các positions đã apply kèm điểm và trạng thái (Pass / Reviewing / Pending scoring)
- **Không** yêu cầu user nhập UUID

---

### Bước 1.9 — Test Intent Classifier (Phase 2)

Các câu kiểm tra routing pattern đã simplify:

| Query | Intent mong đợi | Lý do |
|-------|----------------|-------|
| `"Lương vị trí này bao nhiêu?"` | `jd_analysis` | Pattern `salary` |
| `"Môi trường làm việc thế nào?"` | `jd_analysis` | Pattern `work.*environment` |
| `"Kỹ năng của tôi gồm những gì?"` | `cv_analysis` | Pattern `my skill` |
| `"CV tôi có Spring Boot không?"` | `cv_analysis` | Pattern `my.*spring` |
| `"Vị trí nào phù hợp với tôi?"` | `jd_search` | Pattern `suit.*position` |
| `"Xin chào bạn!"` | `general` | Social phrase |

---

## 2. HR Chatbot

### Bước 2.1 — HR Mode (CV do HR upload)

**Tạo session:**

```http
POST http://localhost:8085/chatbot/hr/session
Content-Type: application/json

{
  "hr_id": "<UUID HR>",
  "position_id": 5,
  "mode": "HR_MODE"
}
```

**Chat:**

```http
POST http://localhost:8085/chatbot/hr/chat
Content-Type: application/json

{
  "session_id": "<session_id HR>",
  "hr_id": "<UUID HR>",
  "position_id": 5,
  "mode": "HR_MODE",
  "query": "Cho tôi top 3 ứng viên Java phù hợp nhất"
}
```

**Kết quả mong đợi:**
- Bot liệt kê đúng số ứng viên (không đếm chunks)
- Mỗi ứng viên là 1 block với heading `Candidate #N`

---

### Bước 2.2 — CV Count (Phase 1 — hard rule)

```http
{
  "query": "Tôi đã upload bao nhiêu CV cho vị trí này?"
}
```

**Kết quả mong đợi:**
- Log: `[Tầng 1] CV count query detected`
- `metadata.is_cv_count_query = true`
- `metadata.function_calls[0].name = "get_cv_summary"`
- Trả lời: "Vị trí ID 5: X CV đã upload | Y đã chấm | Z pass | W fail"

---

### Bước 2.3 — Candidate Mode (ứng viên tự nộp)

**Tạo session:**

```http
POST http://localhost:8085/chatbot/hr/session
{
  "hr_id": "<UUID HR>",
  "position_id": 5,
  "mode": "CANDIDATE_MODE"
}
```

**Chat — hỏi chi tiết ứng viên:**

```http
{
  "session_id": "<session_id>",
  "hr_id": "<UUID HR>",
  "position_id": 5,
  "mode": "CANDIDATE_MODE",
  "query": "Ứng viên Nguyễn Văn A điểm mạnh yếu là gì?"
}
```

---

### Bước 2.4 — Gửi email phỏng vấn (auto-inject position_id + resolve candidateId)

```http
{
  "session_id": "<session_id>",
  "hr_id": "<UUID HR>",
  "position_id": 5,
  "mode": "CANDIDATE_MODE",
  "query": "Gửi email mời phỏng vấn Nguyễn Văn A vào 30/4 lúc 9h"
}
```

**Kết quả mong đợi:**
- Log: `[Tool Inject] Injected position_id=5` + `[Tool Inject] Resolved candidateId=...`
- `metadata.function_calls[0].name = "send_interview_email"`
- Email thực tế được gửi qua SMTP (check MailHog hoặc inbox thật)
- **Không** yêu cầu HR nhập candidateId, positionId

---

### Bước 2.5 — Lọc ứng viên theo tiêu chí (Phase 3 — search_candidates_by_criteria)

```http
{
  "session_id": "<session_id>",
  "hr_id": "<UUID HR>",
  "position_id": 5,
  "mode": "CANDIDATE_MODE",
  "query": "Cho tôi xem ứng viên có điểm >= 80"
}
```

**Kết quả mong đợi:**
- `metadata.function_calls[0].name = "search_candidates_by_criteria"`
- Chỉ liệt kê ứng viên đạt >= 80 điểm
- Hiển thị tên, ID, điểm

**Test thêm — lọc theo kỹ năng:**

```http
{
  "query": "Tìm ứng viên biết Docker trong danh sách"
}
```

```http
{
  "query": "Có ai tên Minh không?"
}
```

---

## 3. Test Lịch sử Chat (Public API)

Gọi vào `recruitment-service (Java - 8080)` — yêu cầu JWT token hợp lệ với header `X-User-Id`.

```http
GET /api/chatbot/sessions?page=0&size=10

GET /api/chatbot/sessions/{sessionId}
```

---

## 4. Test Java Endpoint Trực Tiếp (Phase 3)

### Application Status Endpoint

```http
GET http://localhost:8080/internal/chatbot/candidate/application-status?candidateId=<UUID>
X-Internal-Service: chatbot-service
```

```http
GET http://localhost:8080/internal/chatbot/candidate/application-status?candidateId=<UUID>&positionId=5
X-Internal-Service: chatbot-service
```

**Kết quả mong đợi:**
```json
{
  "code": 1000,
  "data": {
    "candidateId": "...",
    "applications": [
      {
        "positionId": 5,
        "positionName": "Fresher Java Developer",
        "score": 82,
        "status": "Pass"
      }
    ]
  }
}
```

---

## 5. Checklist Regression

| # | Test Case | Expected | Phase |
|---|-----------|----------|-------|
| 1 | Turn 1 JD search → scoring cache | `scored_jobs` populated, Mode A log | P0 |
| 2 | Turn 2 follow-up → cache hit | Mode B log, no Java API call | P0 |
| 3 | Apply với score >= 70 | `finalize_application` called, DB row created | P0 |
| 4 | Apply với score < 70 | Bot từ chối, tool NOT called | P0 |
| 5 | HR hỏi "bao nhiêu CV" | `get_cv_summary` called, đúng con số | P1 |
| 6 | HR gửi email without ID | `send_interview_email` called, candidateId resolved | P1 |
| 7 | _format_cv_context dedup | HR thấy "5 unique candidates", không phải "10 CV blocks" | P0 |
| 8 | Salary sub-intent | `JD_BENEFITS_PROMPT` used | P2 |
| 9 | Process sub-intent | `JD_PROCESS_PROMPT` used | P2 |
| 10 | Improve sub-intent | `CV_IMPROVE_PROMPT` with roadmap | P2 |
| 11 | Candidate hỏi "Tôi đã apply chưa?" | `check_application_status` called | P3 |
| 12 | HR lọc "điểm >= 80" | `search_candidates_by_criteria` called | P3 |
| 13 | JD text không bị truncate 1500 chars | Full section text passed to LLM | P2 |
