# Kế hoạch thực thi (Implementation Plan) - Backend Two-Stage RAG Pipeline

Kế hoạch này vạch ra các bước cần thiết để cấu trúc lại (refactor) Backend của hệ thống CV Review, chuyển đổi sang mô hình **Two-Stage Pipeline (Retrieval -> Reranking)**. 
Dựa trên phản hồi, chúng ta sẽ drop/truncate data cũ trên bảng `cv_analysis` và cấu hình DLQ với 3 retries max.

Để đảm bảo an toàn và dễ debug, công việc sẽ được chia thành **4 Phase** cụ thể. Chúng ta sẽ làm dứt điểm từng Phase, test cẩn thận rồi mới sang Phase tiếp theo.

---

## 🏗️ Phase 1: Clean up & Database Restructuring
*Mục tiêu: Dọn dẹp code thừa và thiết lập các Model/Entity mới.*

1. **Clean up:**
   - Xóa bỏ hoàn toàn thư mục `ai-service` (Kèm theo việc gỡ bỏ các khai báo liên quan trong `docker-compose.yml` nếu có).
2. **Cập nhật Database Entities (`recruitment-service`):**
   - Xóa bỏ trạng thái cũ, cập nhật Enum `CVStatus`: `PENDING`, `EXTRACTING`, `EXTRACTED`, `EMBEDDING`, `EMBEDDED`, `FAILED`.
   - Tạo Enum mới `MatchStatus`: `EXCELLENT_MATCH`, `GOOD_MATCH`, `POTENTIAL`, `POOR_FIT`.
   - Cập nhật `CVAnalysis.java`:
     - Xóa: `Integer score;`
     - Thêm: `Integer technicalScore`, `Integer experienceScore`
     - Thêm: `MatchStatus overallStatus`
     - Thêm: `String learningPath`
3. **Database Migration:**
   - Yêu cầu người dùng (hoặc agent) thực thi câu lệnh SQL: `TRUNCATE TABLE cv_analysis;` để dọn dẹp dữ liệu rác trước khi Hibernate apply cấu trúc cột mới.

---

## 🐇 Phase 2: RabbitMQ & Data Consistency (`recruitment-service`)
*Mục tiêu: Xây dựng luồng xử lý bất đồng bộ, chống Rate Limit và đảm bảo tính toàn vẹn.*

1. **Cấu hình RabbitMQ (common-library & recruitment-service):**
   - Định nghĩa các hằng số và Queues:
     - `cv.extract.queue` (kèm DLQ logic: max retries = 3).
     - `cv.embed.queue` (kèm DLQ logic: max retries = 3).
     - `cv.embed.reply.queue`.
   - Giới hạn `concurrency` và `prefetch count = 1` cho các extraction listener.
2. **Refactor CV Upload Flow:**
   - Khi CV được upload, thay vì xử lý đồng bộ, lưu DB với `CVStatus = PENDING` và đẩy event `CVUploadEvent` vào `cv.extract.queue`.
3. **Tạo Listeners mới:**
   - `ExtractCVListener`: Lắng nghe `cv.extract.queue`. Nhận CV -> Update `EXTRACTING` -> Gọi `GeminiExtractionService` lấy Metadata -> Cập nhật `EXTRACTED` -> Đẩy payload sang `cv.embed.queue`.
   - `EmbedReplyListener`: Lắng nghe `cv.embed.reply.queue` (từ Python service trả về). Cập nhật DB thành `EMBEDDED` hoặc `FAILED`.

---

## 🐍 Phase 3: Embedding Service Integration (`embedding-api`)
*Mục tiêu: Python service nhận metadata và nhúng vào Qdrant.*

1. **Cập nhật RabbitMQ Consumer:**
   - Lắng nghe `cv.embed.queue`.
   - Nhận payload gồm: Text đã parse của CV + JSON Metadata (từ bước extract).
2. **Qdrant Upsert:**
   - Đẩy Vector embeddings vào Qdrant và đính kèm Metadata JSON vào payload của vector trên Qdrant.
3. **Reply Event:**
   - Gắn cờ thành công hoặc thất bại và publish message trả lại vào `cv.embed.reply.queue` cho Java service.

---

## 🤖 Phase 4: Two-Stage Pipeline & Chatbot Features (`chatbot-service`)
*Mục tiêu: Triển khai lõi RAG Retrieval & Reranking với tính năng mới.*

1. **Retrieval (Hybrid Search):**
   - Chỉnh sửa logic truy vấn Qdrant.
   - Ứng dụng Hybrid Search: Lọc bằng Metadata trước, sau đó mới dùng Cosine Similarity lấy Top 5 JDs (hoặc CVs).
2. **Reranking (Đánh giá đa chiều):**
   - Refactor tool `evaluate_cv_fit`. Cập nhật Prompt để gọi LLM chấm điểm theo cấu trúc đa chiều thay vì điểm đơn (Score).
3. **Killer Features (XAI & Learning Path):**
   - Chỉnh sửa Prompt yêu cầu AI trả về đoạn JSON chứa trích dẫn (highlight) và gợi ý lộ trình học tập khi trạng thái là `POOR_FIT` hoặc `POTENTIAL`.

---

## Verification Plan
* Quy trình làm việc: Sau mỗi Phase, tôi sẽ tạo một task list và chúng ta sẽ cùng review code trước khi apply thay đổi.
* Bước đầu tiên (Phase 1) yêu cầu việc xóa service và đổi Entity, vì vậy hãy xác nhận Plan này để tôi tiến hành xóa `ai-service` và update code Java cho Phase 1.
