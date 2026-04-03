4. Thiết kế Luồng Chatbot HR dùng Vector DB (ĐIỂM SÁNG CỦA ĐỒ ÁN)
Kiến trúc bạn vẽ ra thực sự rất xịn và có tính ứng dụng thực tế cực cao. Dưới đây là cách bạn sẽ Code nó:

4.1. Lọc top 10-20 CVs kết hợp Vector DB (Metadata Filtering)

Khi HR gõ: "Lọc cho tôi 5 bạn có kinh nghiệm về cloud tốt nhất".
Làm sao Qdrant biết chỉ tìm trong 20 bản CV xịn vừa đỗ? Dùng tính năng Payload Filter của Qdrant!
Khi query VectorDB, bạn truyền ID của 20 CV đó vào Filter:
json
"filter": {
    "must": [
        { "key": "cvId", "match": { "any": [12, 45, 88, 102, ...] } }
    ]
}
Nhờ vậy, Qdrant chính xác 100% sẽ chỉ lục lọi Semantic search (kinh nghiệm Cloud) bên trong tập 20 CVs mà HR đang quan tâm. Rất nhanh và chuẩn!
4.2 Vụ tự động hóa Gửi Email (AI Agent / Function Calling)

Chỗ này bạn có thể xài Gemini Function Calling. Bạn định nghĩa 1 tool send_interview_email(list_cv_ids) và send_reject_email(list_cv_ids). Khi HR chat "Gửi mail đậu cho 5 bạn top đầu, đánh rớt các bạn còn lại", LLM tự nhận diện intent và trả về JSON chứa list ID.
Backend của bạn bắt được ID đó $\rightarrow$ Trích xuất tên, email ứng viên $\rightarrow$ Gọi SMTP server gửi email hàng loạt. (Điểm 10 Đồ án ở tính năng Automation này!).
4.3 Bài toán "Giữ Context" cho Chatbot 10 Câu Hỏi (Window Memory)

Giải pháp bạn nghĩ ra là hoàn toàn chính xác và thực tế. Sinh viên thì không cần làm các hệ thống Long-term Memory bằng GraphDB hay Vectorized Memory làm gì cho phức tạp.
Cách Làm (In-Memory Session):
Khi HR mở khung Chat, bên Web Frontend (React/Vue/Angular) khởi tạo 1 mảng tĩnh: const messages = [];
Mỗi lần HR chat câu thứ 1, 2, 3... Frontend push tin nhắn vào mảng messages, và gửi cả cái mảng đó lên API của Backend.
Backend nhận List Messages, thêm Prompt bối cảnh + Data tìm được từ Qdrant, ném tất cả vào LLM. Vì số lượng câu ít (khoảng 10-20 câu), LLM dư sức nhớ vì nó được đọc lại toàn bộ lịch sử 10 câu đó từ đầu!
Khi HR bấm "Kết thúc ca làm việc / Thoát chat": Frontend gửi tín hiệu POST /api/chat/save.
Backend lấy chuỗi list messages đó chuyển thành JSON String [{role: "user", text: "..."}, {role: "model", text: "..."}] và lưu vào 1 bảng Database thông thường chat_history.
Format lưu DB đúng như ý bạn: ID | Title (Senior Java - 20/4 - HR Chi) | Content (JSON) $\rightarrow$ Cực đơn giản, dễ làm UI xem lịch sử, không phức tạp hóa vấn đề.