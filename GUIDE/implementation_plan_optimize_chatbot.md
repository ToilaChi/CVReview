# Implementation Plan: Tối ưu hoá Chatbot RAG (Candidate & HR Modes)

Bản kế hoạch này phân tách quá trình fix bugs và tối ưu hoá hệ thống thành 4 Phase rõ ràng, đi từ các sửa đổi nhỏ, hiệu quả cao (Low-hanging fruits) đến các thay đổi sâu về mặt kiến trúc.

## User Review Required

> [!IMPORTANT]
> Bạn vui lòng đọc qua các Phase dưới đây. 
> Đặc biệt chú ý **Phase 3** (Liên quan đến việc thêm trường `applied_position_ids` vào Qdrant) vì nó có thể đòi hỏi phải chạy script sync lại dữ liệu từ MySQL qua Qdrant.

## Kế hoạch triển khai (Phases & Tasks)

---

### Phase 1: Fix Candidate Chatbot Scoring Logic (High Priority)
*Mục tiêu: Xử lý ngay lỗi ứng viên Overqualified (Mid/Senior apply Intern) bị đánh trượt hoặc khuyên sai.*

#### [MODIFY] `chatbot-service/app/rag/prompts.py` (hoặc file chứa prompt chấm điểm)
- **Cập nhật SYSTEM_PROMPT cho logic chấm CV:** Thêm rule "Hierarchical Skill Inference".
- **Rule chi tiết:** Nếu ứng viên có kỹ năng nâng cao (vd: React, Microservices) mà JD yêu cầu kỹ năng cơ bản (HTML/CSS, basic Java), thì tự động xem như thỏa mãn yêu cầu cơ bản. Không được trừ điểm. Hãy cho điểm cao nhưng gắn label là 'Overqualified' và gợi ý vị trí cao hơn.

---

### Phase 2: HR Chatbot - Data Isolation & Email State Machine (High Priority)
*Mục tiêu: Đảm bảo không bị lẫn lộn dữ liệu giữa ứng viên tự apply và HR upload, đồng thời xử lý vòng lặp lỗi khi gửi email.*

#### [MODIFY] `chatbot-service/app/rag/retriever.py` (hoặc module xử lý Qdrant search)
- **Enforce Global Filter:** Xây dựng base wrapper cho Qdrant search để luôn luôn chèn `sourceType` tương ứng với `mode` của session (HR_MODE -> `sourceType=HR`, CANDIDATE_MODE -> `sourceType=CANDIDATE`). Cấm việc LLM tự do query mà không qua filter này.

#### [MODIFY] `chatbot-service/app/rag/hr_graph.py` (LangGraph HR Workflow)
- **Thêm Email Confirmation State:** Khai báo thêm trường `pending_email_action` vào `HRChatState`.
- **Cập nhật logic Confirm:** Khi LLM dự đoán tool `send_interview_email`, graph rẽ nhánh (conditional edge) sang trạng thái Confirm và hỏi HR ("Bạn có đồng ý gửi email?"). Nếu user trả lời "Đồng ý", graph móc data từ `pending_email_action` ra thực thi thay vì để LLM tự reasoning lại.

---

### Phase 3: Candidate Mode Architecture - Missing PositionId (Medium Priority, High Impact)
*Mục tiêu: Thay vì phải truy vấn SQL lấy hàng ngàn `candidateId` rồi nhét vào Qdrant (làm hệ thống chậm và dễ crash), ta sẽ tối ưu hoá payload trên Qdrant.*

#### [MODIFY] `embedding-service/app/worker_cv.py` (hoặc file đẩy data lên Qdrant)
- **Thêm `applied_position_ids`:** Cập nhật payload của Master CV trên Qdrant để chứa mảng `applied_position_ids` (mặc định là rỗng).

#### [MODIFY] `recruitment-service/../ApplicationService.java` (hoặc nơi xử lý logic Apply)
- **Sync data khi Apply:** Khi Candidate bấm Apply (tạo Application CV), gửi event/message để update bổ sung `positionId` vào mảng `applied_position_ids` của Master CV tương ứng trên Qdrant.

#### [MODIFY] `chatbot-service/app/rag/hr_tools.py` & Retrieval Logic
- **Đổi logic filter:** Thay đổi cách mode CANDIDATE filter. Thay vì lấy mảng `candidateId` khổng lồ, ta chỉ cần query thẳng Qdrant với: `MatchAny(applied_position_ids=[session.position_id])`.

---

### Phase 4: JD Chunking & Factual Metadata (Medium Priority)
*Mục tiêu: Chatbot có thể trả lời chính xác các câu hỏi như "Mức lương bao nhiêu", "Phỏng vấn mấy vòng" thay vì dựa vào vector search (dễ mất context).*

#### [MODIFY] `embedding-service/app/worker_jd.py` (hoặc LlamaParse config)
- **Metadata Extraction:** Khi parse JD, dùng LLM tách riêng các thông tin `salary_range`, `benefits`, `interview_process`. Lưu các thông tin này vào Metadata của Qdrant hoặc vào Database SQL.

#### [NEW] `chatbot-service/app/rag/candidate_tools.py`
- **Tool `get_jd_details`:** Tạo thêm tool cho Candidate Chatbot (giống `get_candidate_details` của HR). Khi ứng viên hỏi về lương/quy trình, LLM sẽ gọi tool này móc data metadata JSON ra trả lời chính xác 100%.

## Verification Plan

### Automated Tests
- Gửi 1 CV Senior vào JD Intern và kiểm tra `evaluate_cv_fit` trả về score > 80 và có label overqualified.
- Mock query HR chatbot yêu cầu gửi email và kiểm tra LangGraph có chặn lại ở node Confirmation hay không.

### Manual Verification
- HR Chatbot Mode Candidate: Test query với `position_id` xem tốc độ retrieval có nhanh hơn và chuẩn xác không (sau khi apply Phase 3).
- Candidate Chatbot: Hỏi câu "Mức lương cho vị trí này là bao nhiêu?", kỳ vọng trả lời chuẩn xác, không bịa.
