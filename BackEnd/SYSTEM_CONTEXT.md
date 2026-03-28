# CV Review System - Full Context (Phase 1 & 2 Completed)

## 1. Tổng quan hệ thống
Hệ thống tự động hóa sàng lọc CV cho HR và hỗ trợ nghề nghiệp cho Candidate dựa trên AI.
- **Kiến trúc:** Microservices (Spring Boot & FastAPI).
- **Giao tiếp:** Bất đồng bộ qua RabbitMQ (có cơ chế Retry & Dead Letter Queue).
- **Lưu trữ:** MySQL (RDBMS), Qdrant (Vector DB), Google Drive (File Storage).

## 2. Các Service đã triển khai
1. **Api-gateway:** Điều hướng, xác thực JWT.
2. **Auth-service:** Quản lý User (HR/Candidate), Role-based Access Control.
3. **Recruitment-service (Core):** - Quản lý Position/JD & CV.
   - Tích hợp LlamaParse để chuyển đổi CV/JD sang Markdown.
   - Quản lý Batch Processing & Tracking (POS{id}_{date}_B{uuid}).
4. **Ai-service:** - Tiêu thụ message từ RabbitMQ.
   - Sử dụng Gemini/Groq (Llama-3.3-70b) để chấm điểm CV theo tiêu chí: Core Fit (60%), Experience (30%), Qualification (10%).
5. **Embedding-service & Worker:** - Chuyển đổi nội dung CV/JD thành Vector (Model: BGE-small-en-v1.5).
   - Lưu trữ vào Qdrant (Collections: JD embeddings, CV embeddings).
6. **Chatbot-service (RAG):**
   - Sử dụng LangGraph & RAG.
   - Hỗ trợ Candidate tìm việc (Intent: `jd_search`) và tư vấn sửa CV (Intent: `cv_analysis`).

## 3. Luồng nghiệp vụ hiện tại
- **HR Flow:** Upload JD/CV -> Parsing -> Chunking -> Embedding -> AI Analysis/Scoring -> Dashboard Tracking.
- **Candidate Flow:** Upload CV -> Parsing -> Chunking -> Embedding -> Chatbot tư vấn (Matching Job/Skill Gap Analysis).  

## 4. Công nghệ & Thông số kỹ thuật
- **AI Models:** Gemini-1.5-Flash, Llama-3.3-70b (Groq), BGE Embeddings.
- **Parsing:** LlamaParse API (Polling 2s, timeout 60s).
- **Threshold:** Điểm đạt yêu cầu phỏng vấn >= 75.