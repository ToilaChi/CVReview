# Chatbot Feature — Hướng dẫn Testing Toàn diện

Tài liệu này hướng dẫn cách test toàn bộ feature Chatbot mà chúng ta đã xây dựng qua 4 phases. Hệ thống hiện tại đã sử dụng cơ chế **Session-based**, tách biệt giữa **Candidate Chatbot** và **HR Chatbot (với 2 modes)**.

Do đó, lộ trình test sẽ chia thành 2 phần chính. Bạn nên sử dụng **Postman** (hoặc Insomnia / cURL) để test trực tiếp vào cổng của API Gateway (hoặc bắn trực tiếp qua các service).

---

## 1. Test Candidate Chatbot (Tư vấn viên cho Ứng viên)

> Flow: Ứng viên bắt đầu cuộc hội thoại -> Hỏi về công việc phù hợp -> Nộp đơn ứng tuyển -> Ứng tuyển thành công (hoặc thất bại nếu chuẩn <= 70).

### Bước 1.1: Tạo Session
Gọi API tạo Session mới cho Candidate qua Chatbot Service (Python - 8085).
* **Endpoint:** `POST http://localhost:8085/chatbot/candidate/session`
* **Body:**
```json
{
  "user_id": "<UUID của User/Candidate>"
}
```
* **Kết quả:** Trả về `session_id`. Hãy copy `session_id` này.

### Bước 1.2: Chat & Hỏi về JDs
Sử dụng `session_id` để tương tác.
* **Endpoint:** `POST http://localhost:8085/chatbot/candidate/chat`
* **Body:**
```json
{
  "session_id": "<session_id từ bước trên>",
  "query": "Tôi muốn phân tích và tìm công việc phù hợp với kỹ năng Java của mình",
  "candidate_id": "<UUID của Candidate>"
}
```
* **Kết quả:** 
  1. Assistant trả về phản hồi natural language.
  2. Ở `metadata.function_calls` hoặc `metadata.scored_jobs` sẽ thấy list đánh giá theo từng điểm số của active `Position`.

### Bước 1.3: Yêu cầu nộp đơn
* **Endpoint:** `POST http://localhost:8085/chatbot/candidate/chat`
* **Body:**
```json
{
  "session_id": "<session_id gắn với flow trên>",
  "query": "Thật tuyệt, hãy giúp tôi apply vào vị trí Java Developer theo phân tích đó",
  "candidate_id": "<UUID của Candidate>"
}
```
* **Kết quả:** LLM gọi Tool `finalize_application`. Python Service sẽ gọi qua Recruitment (Java) API để sinh ra `Application CV`. Bạn hãy check thử `CandidateCV` database xem row Application CV mới có sinh ra hay không.

---

## 2. Test HR Chatbot (Hỗ trợ Tuyển dụng cho HR)

> Flow: HR quản lý các CV cho một đợt tuyển dụng (Position ID) theo 2 mode (ứng tuyển / nội bộ HR).

### Bước 2.1: Test HR_MODE (Kho CV Sourced của HR)
Mode này chỉ query CV do HR tải lên (`sourceType = HR`).

1. **Tạo session cho vị trí 5:**
```json
// POST http://localhost:8085/chatbot/hr/session
{
  "hr_id": "<UUID của HR>",
  "position_id": 5,
  "mode": "HR_MODE"
}
```
2. **Chat:**
```json
// POST http://localhost:8085/chatbot/hr/chat
{
  "session_id": "<session_id HR mới tạo>",
  "hr_id": "<UUID của HR>",
  "position_id": 5,
  "mode": "HR_MODE",
  "query": "Có ứng viên nào có kinh nghiệm Spring Boot 2 năm không?"
}
```

### Bước 2.2: Test CANDIDATE_MODE (Đánh giá CV ứng viên tự đẩy)
Truy vấn các CV mà Candidate đã tự gọi tool Apply thành công (`sourceType = CANDIDATE`).

1. **Tạo session mới cho Candidate Mode:**
```json
// POST http://localhost:8085/chatbot/hr/session
{
  "hr_id": "<UUID của HR>",
  "position_id": 5,
  "mode": "CANDIDATE_MODE"
}
```
2. **Hỏi chi tiết & Ra lệnh gửi mail:**
```json
// POST http://localhost:8085/chatbot/hr/chat
{
  "session_id": "<session_id>",
  "hr_id": "<UUID của HR>",
  "position_id": 5,
  "mode": "CANDIDATE_MODE",
  "query": "Ứng viên Nguyễn Văn A có điểm mạnh yếu là gì? Hãy gửi email mời anh ta đi phỏng vấn ngày mai nha."
}
```
* **Kết quả:** Python Chatbot sử dụng tool `get_candidate_details` (Lấy Data từ API Java) > Gọi Tool `send_interview_email` > Bắn mail thực tế qua SMTP Java. Bạn có thể mở hòm mail hoặc dùng MailHog local để test.

---

## 3. Test Lịch sử Chat (Public API)
Bắn vào `Recruitment Service (Java - 8080)` để test giao diện hiển thị cho FE. *Yêu cầu phải có Header JWT Token hợp lệ chứa `Role` và parse được `X-User-Id` trên API Gateway.*

1. **Lấy danh sách các History Groups của User:**
   `GET /api/chatbot/sessions?page=0&size=10`
2. **Trích xuất Message theo session để Load lại chat:**
   `GET /api/chatbot/sessions/{sessionId}`
