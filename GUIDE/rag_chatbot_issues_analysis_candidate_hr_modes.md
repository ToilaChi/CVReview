# RAG Chatbot Issues Analysis (Candidate & HR Modes)

## Overview
Tài liệu này tổng hợp các vấn đề hiện tại trong hệ thống chatbot (Candidate chatbot & HR chatbot), phân tích nguyên nhân gốc rễ và đề xuất hướng xử lý để có thể tiếp tục hỏi AI (Antigravity) hoặc cải tiến hệ thống.

---

# I. Candidate Chatbot Issues

## 1. Intern/Fresher Rigid Scoring Problem

### Hiện tượng
- Candidate overqualified (Mid/Senior) apply vào Intern/Fresher bị chấm điểm thấp.
- Chatbot đưa ra lời khuyên sai ("cần học thêm") thay vì khuyên apply vị trí cao hơn.

### Nguyên nhân gốc
1. **Skill Matching Bias**
   - So sánh embedding giữa CV và JD theo exact skill match.
   - JD Intern thường chứa skill cơ bản → không match với skill nâng cao trong CV.
   - Vector similarity bị giảm dù candidate thực tế mạnh hơn.

2. **Lack of Level Awareness**
   - Hệ thống không có khái niệm "overqualified".
   - Không có mapping giữa level (Intern → Fresher → Mid → Senior).

3. **Prompt chưa đủ mạnh**
   - JD_SEARCH_PROMPT chưa ép LLM suy luận theo hierarchy level.

### Hệ quả
- Scoring không monotonic (Mid có thể thấp hơn Intern).
- Recommendation vô lý.

### Đề xuất giải pháp

#### 1. Prompt Fix (bắt buộc)
Thêm rule rõ ràng vào JD_SEARCH_PROMPT:

"If a candidate is overqualified for a lower-level position:
- Do NOT penalize missing basic skills
- Infer that higher-level skills imply lower-level competency
- Give a high score
- Strongly recommend applying for a higher-level role instead of suggesting to learn more"

#### 2. Level Normalization (quan trọng)
- Mapping skill theo level:
  - Senior > Mid > Fresher > Intern
- Nếu candidate có skill ở level cao hơn → auto satisfy level thấp hơn.

---

## 2. Không search được Salary / Selection Process

### Hiện tượng
- Các section như salary, interview process có trong JD nhưng chatbot không trả lời được.

### Nguyên nhân gốc

1. **Chunking Issue**
- JD bị chunk nhỏ → mất context
- "Salary" hoặc "Process" nằm trong chunk không được retrieve

2. **Embedding Semantic Gap**
- Query: "mức lương" vs text: "compensation", "salary range"
- Embedding không match mạnh

3. **Retrieval thiếu recall**
- Top-k thấp
- Không có hybrid search (keyword + vector)

4. **Metadata không được tận dụng**
- Salary/process là structured data nhưng đang treat như text

### Đề xuất giải pháp

#### 1. Improve Chunking
- Chunk theo section:
  - Salary
  - Benefits
  - Interview Process

#### 2. Hybrid Search
- Combine:
  - Vector search (Qdrant)
  - Keyword search (BM25 hoặc filter)

#### 3. Metadata Extraction
- Lưu riêng:
  - salary_range
  - interview_process
- Query → ưu tiên lookup metadata trước

#### 4. Query Expansion
- Map synonym:
  - "mức lương" → salary, compensation, pay

---

# II. HR Chatbot Issues

## 1. Lẫn dữ liệu giữa HR mode và Candidate mode

### Hiện tượng
- Query lấy candidate bị trộn giữa:
  - HR upload
  - Candidate apply

- Một số query đúng, một số query sai:
  - Query đơn giản → sai
  - Query reasoning → đúng

### Nguyên nhân gốc

1. **Thiếu filter ở Retrieval Layer**
- Không filter theo source_type trước khi search

2. **SQL vs Vector inconsistency**
- Query đơn giản → dùng SQL → bị lẫn
- Query phức tạp → dùng Qdrant → đúng

3. **source_type chưa enforce global**
- Chỉ apply ở một số pipeline

### Đề xuất giải pháp

#### 1. Hard Filter (bắt buộc)
- Luôn filter:
  source_type = HR
- Apply ở:
  - SQL
  - Qdrant

#### 2. Unified Retrieval Pipeline
- Không để:
  - Query A → SQL
  - Query B → Vector
- Phải thống nhất pipeline

#### 3. Debug Logging
Log rõ:
- source_type
- query_type
- retrieval_source

---

## 2. Email Confirmation Loop

### Hiện tượng
- Sau khi gửi email vẫn hỏi lại confirm
- Không gửi được email dù đã confirm

### Nguyên nhân gốc

1. **State Management Issue**
- Không lưu trạng thái:
  - pending
  - confirmed
  - sent

2. **Confirmation không trigger action**
- "Đồng ý" không map đúng intent

3. **Session Memory bị mất**
- Chatbot không nhớ đã gửi email

### Đề xuất giải pháp

#### 1. Email State Machine

Trạng thái:
- NONE
- PENDING_CONFIRM
- SENT

Logic:
- Nếu SENT → không hỏi lại
- Nếu user yêu cầu gửi lại → warn

#### 2. Intent Mapping
- "Đồng ý" → CONFIRM_EMAIL

#### 3. Persist State
- Lưu DB hoặc Redis

---

# III. Candidate Mode (Critical Architecture Issue)

## 1. Missing positionId trong Qdrant

### Hiện tượng
- CV embedding không có positionId
- Không thể filter theo job

### Hệ quả

1. Query theo position → sai dữ liệu
2. Phải fallback SQL → gây lẫn data
3. Inconsistent behavior giữa các query

### Root Cause
- positionId chỉ tồn tại ở SQL
- Không sync sang vector DB

### Đề xuất giải pháp

#### 1. Add Metadata vào Qdrant (bắt buộc)

Payload cần có:
- cvId
- candidateId
- positionId
- source_type

#### 2. Re-index toàn bộ data
- Re-embed hoặc update payload

#### 3. Enforce Filter
- Khi search:
  filter:
    positionId = X

---

## 2. Inconsistent Answering Behavior

### Hiện tượng

- Query 1:
  "Tìm 3 ứng viên phù hợp nhất" → sai

- Query 2:
  "Ai có khả năng lên Mid nhanh" → đúng

### Nguyên nhân

- Query 1:
  → SQL filtering → sai

- Query 2:
  → Vector search → đúng

### Insight quan trọng

**System hiện tại có 2 pipeline khác nhau:**

1. SQL-based retrieval
2. Vector-based retrieval

→ Không đồng nhất → gây bug

### Giải pháp

#### 1. Single Source of Truth
- Ưu tiên Qdrant làm retrieval chính

#### 2. SQL chỉ dùng cho metadata lookup

#### 3. Bắt buộc filter trong vector search

---

# IV. Tổng kết các vấn đề chính

## 1. Thiếu metadata trong vector DB
- positionId
- source_type

## 2. Retrieval pipeline không đồng nhất
- SQL vs Vector

## 3. Prompt chưa đủ mạnh
- Không xử lý overqualified

## 4. State management yếu
- Email flow

## 5. Chunking & embedding chưa tối ưu
- Không retrieve được section quan trọng

---

# V. Action Plan (Ưu tiên triển khai)

## Priority 1 (Critical)
- Add positionId + source_type vào Qdrant
- Re-index data
- Enforce filter ở mọi query

## Priority 2
- Fix prompt xử lý overqualified
- Improve chunking JD

## Priority 3
- Hybrid search
- Metadata extraction (salary, process)

## Priority 4
- Email state machine

---

# VI. Câu hỏi gợi ý để hỏi AI (Antigravity)

1. Làm sao thiết kế unified retrieval pipeline cho RAG (SQL + Vector)?
2. Cách implement hierarchical skill matching (Senior > Intern)?
3. Best practice để lưu metadata trong Qdrant?
4. Cách detect intent "overqualified" trong LLM?
5. Thiết kế state machine cho chatbot action (email, approval)?

---

**End of Document**

