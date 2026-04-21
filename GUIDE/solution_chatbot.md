## Để xử lý dứt điểm tình trạng "trả lời lan man" của Candidate Chatbot và lỗi "không có data" của HR Chatbot, chúng ta cần một đợt refactor cấu trúc.

A. Thiết kế lại Intent Classification (Thay thế Regex):

Hãy loại bỏ hoàn toàn việc dùng Regex cứng nhắc trong intent.py.

Dùng LLM-based Router (có thể dùng model nhẹ như gemini-1.5-flash cho tiết kiệm) hoặc Semantic Router (tận dụng EmbeddingService sẵn có) để phân loại câu hỏi vào các intent cụ thể hơn như: job_match (Tìm việc), salary_benefits (Lương & Phúc lợi), company_culture (Văn hóa công ty), application_status (Trạng thái nộp đơn) v.v..

B. Cấu trúc lại RAG Workflow theo hướng Agentic (Sử dụng Tools):

Thay vì nhồi nhét mọi thứ vào 1-2 node và prompt, hãy chia nhỏ luồng xử lý:

Candidate Chatbot:

Tạo các Tools mới như: get_job_salary, get_company_benefits, check_application_status.

Node scoring_node hiện tại (dùng gemini-2.5-pro) có thể đóng gói thành tool evaluate_cv_fit.

LLM ở node chính sẽ nhận câu hỏi, tự phân tích intent và quyết định gọi Tool nào để lấy thông tin. Ví dụ: Nếu hỏi lương -> Gọi Tool lấy lương -> Tool trả về con số -> LLM sinh câu trả lời ngắn gọn.

HR Chatbot:

Cũng bổ sung các Tool như trên Candidate chatbot, ví dụ: search_candidates(position_id, criteria) để LLM có thể chủ động search ứng viên theo yêu cầu, thay vì chỉ bị động nhận list IDs từ SQL hay Qdrant.

C. Tối ưu Retrieval (Giải quyết vấn đề phình Context):

Đảm bảo retrieve_context_node chỉ trả về những chunk CV/JD thật sự liên quan (Semantic Search).

Nếu LLM cần chấm điểm (ví dụ gọi tool evaluate_cv_fit), lúc đó Tool mới load full JD text từ database để chấm. Không nhét full JD vào context chung của toàn bộ phiên chat.

## Xử lý vấn đề chấm điểm bị lệch: Cấu hình lại prompt cho LLM chấm điểm: Tôi muốn giống như đang làm trong Candidate Graph, chấm phải có sự gắt trong đó.