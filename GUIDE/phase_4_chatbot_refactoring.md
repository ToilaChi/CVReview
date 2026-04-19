# Phase 4 Chatbot Refactoring: Small-to-Big RAG & LLM Routing

Tài liệu này tổng hợp ngữ cảnh, phân tích kỹ thuật và lộ trình triển khai (Implementation Plan) cho đợt Refactoring hệ thống Chatbot/RAG tiếp theo, giúp giữ nguyên Context khi chuyển sang Conversation mới.

---

## 1. Ngữ cảnh (Context) & Vấn đề hiện tại
Hệ thống Chatbot RAG (tư vấn Candidate và HR) hiện tại đã hoạt động luồng cơ bản nhưng gặp 3 "nút thắt" lớn về hiệu năng và logic:

1. **Vấn đề Vector Loãng (Semantic Dilution) ở JD:** Dữ liệu JD (Job Description) dài ~6000-10000 ký tự đang được ném nguyên cục vào Qdrant dể nhúng (Embedding) mà không qua Chunking. Điều này khiến các model nhúng bị quá tải token (silent truncation) và "pha loãng" ý nghĩa. Khi Candidate hỏi về một kỹ năng cụ thể, Qdrant search không thấy hoặc điểm cực thấp dẫn đến LLM chấm rớt.
2. **Vấn đề Scoring lỏng lẻo & Ngân sách LLM:** Tính toán matching (CV vs JD) yêu cầu tư duy logic sâu. Model nhẹ (Flash) thường lười đếm skill và dễ sinh "ảo giác" (hallucination). Hệ thống cần chuyển `scoring_node` sang dùng Model mạnh (Gemini 1.5 Pro). Để tối ưu chi phí, hệ thống chỉ gọi Pro 1 lần và Cache kết quả lại cho các lượt trò chuyện sau (rẻ tiền hơn, dùng Flash).
3. **Hiển thị Tên Vị trí khi Ứng tuyển:** Ở chức năng `finalize_application`, Chatbot cần xác nhận bằng một format chuỗi tên Position mịn màng và chính xác (`f"{Name} {Language} {Level}"`) với hỗ trợ submit nhiều vị trí cùng lúc.

---

## 2. Giải pháp Kiến trúc: "Small-to-Big Retrieval" & "Tiered LLM Routing"
- **Markdown Chunking cho JD:** Chuyển xử lý JD về đúng Flow của CV. Dùng LlamaParse bóc tách JD thành Markdown, băm thành từng Section Chunk (Overview, Requirements, v.v.).
- **Small-to-Big Retrieval:** Qdrant chỉ lưu và search trên các Fragment nhỏ (Chunks). Khi Qdrant tìm được Chunk "Requirements" của vị trí số 15 -> Python chiết xuất `positionId = 15` -> Gọi HTTP Request về Backend SQL để móc lên **Bản JdText Nguyên Vẹn (Parent)** -> Ném nguyên bản này cho LLM Pro xơi. Vừa search cực nhạy, vừa cung cấp đủ context cho LLM mà không sợ mất chữ.
- **Tiered LLM Routing:** Hệ thống khai báo 2 biến môi trường: `SCORING_GEMINI_MODEL=gemini-2.5-pro` (Dùng cho toán chấm điểm) và `GEMINI_MODEL=gemini-2.5-flash` (Dùng cãi tay đôi với Candidate).

---

## 3. Implementation Plan & Tasks Checklist 

### Giai đoạn 1: Sửa Backend Java (`recruitment-service`)
- [ ] **Sửa LlamaParse config:** Trong `LlamaParseClient.java`, chỉnh `uploadFileForJD()` để ép LlamaParse xuất file Markdown giữ nguyên các thẻ Headers (`#`, `##`) tương tự cấu hình của CV.
- [ ] **Tạo DTO mới:** Viết `JDChunkPayload` và Event Messaging `JDChunkedEvent` (chứa mảng các Chunks).
- [ ] **Tái sử dụng Chunking Logic:** Bổ sung logic hoặc extends `ChunkingService` để băm JD Markdown Text thành các đoạn Sections riêng biệt.
- [ ] **Publish Event Mới:** Tại `PositionService`, thay vì quăng nguyên bản JD Text vào queue, hãy gọi Chunking và gửi `JDChunkedEvent` xuống hệ thống RabbitMQ.
- [ ] **Bổ sung API kéo Text gốc:** Tại `ChatbotInternalController`, thêm `POST /internal/chatbot/positions/details` nhận mảng `[id1, id2]` và trả ra danh sách Object Full Text JD (`id`, `name`, `language`, `level`, `jdText`).

### Giai đoạn 2: Sửa Python Consumer (`embedding-service`)
- [ ] **Sửa Consumer:** Xóa bỏ cách xử lý cũ trong `jd_consumer.py`. Chuyển sang lắng nghe `JDChunkedEvent`.
- [ ] **Insert Multiple Points:** Biến đổi 1 message (JD Chunked Event) của Java thành 1 vòng lặp `for` nạp Embedding và lưu nhiều điểm (Points) vào Qdrant (VD: `jd_{id}_chunk_0`, `jd_{id}_chunk_1`) nhưng giữ chung metadata/payload `jdId`, thêm field tên Position.

### Giai đoạn 3: Cải tổ RAG Graph (`chatbot-service`)
- [ ] **Update Config:** Khai báo cấu hình riêng `SCORING_GEMINI_MODEL` trong `app/config.py`.
- [ ] **Update Recruitment API Client:** Thêm phương thức `get_position_details(position_ids: List[int])` vào `recruitment_api.py`.
- [ ] **Sửa Node 0 (Tách Name Reference):** `load_session_history_node` cần lưu thêm Map Reference `{id: name + language + level}` để dùng về sau.
- [ ] **Sửa Node 2 (Retriever):** Trích xuất danh sách các `jdId` KHÔNG TRÙNG LẶP từ kết quả Chunk mà Qdrant trả về. Lấy list ID đó gọi API `get_position_details()` tạo mảng Context Full JdText dầy đủ thay thế cho mảng Chunk đứt khúc.
- [ ] **Sửa Node Điểm sô (Scoring):** Chuyển model khởi tạo sang `SCORING_GEMINI_MODEL`, tháo bỏ giới hạn cắt nội dung dư thừa `[:800]` như cũ.
- [ ] **Quy chuẩn UX Finalize:** Ở Node 4, loop qua tool arg lấy `position_id`, rọi qua list Dictionary reference để map ra text String chuẩn xác. Build lại final string không có kí tự markdown dư thừa.

### Giai đoạn 4: Dọn dẹp Database Cloud (Thao tác tay của User)
- [ ] Xóa/Update lại các Position Job Desciption trên UI hệ thống để trigger RabbitMQ đè/tạo lại dữ liệu Vector theo cấu trúc Chunks mới nhất.
