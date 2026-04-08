# Ý tưởng Thiết kế Role Admin cho Hệ thống CV Review

Dựa trên việc phân tích kiến trúc Microservices, luồng dữ liệu bất đồng bộ (RabbitMQ) và các dịch vụ AI (LlamaParse, Gemini, Qdrant) của hệ thống **CV Review**, dưới đây là bản đề xuất các tính năng và nghiệp vụ cốt lõi dành cho **Role Admin**.

Mục tiêu chính của Admin không chỉ là quản lý người dùng (HR/Candidate) mà còn đóng vai trò **giám sát sức khỏe hệ thống**, **quản lý chi phí/trạng thái API AI**, và **xử lý các điểm nghẽn (bottleneck) trong luồng xử lý CV**.

---

## 1. Quản lý Người dùng & Phân quyền (User & Access Management)
Tính năng cơ bản để quản trị các nhóm người dùng trong hệ thống (gắn với `auth-service`):
- **Quản lý tài khoản HR & Candidate:** Xem danh sách, tìm kiếm, khóa (ban/block) hoặc xóa (soft-delete) các tài khoản vi phạm.
- **Kiểm duyệt HR (Tùy chọn):** Không cần kiểm duyệt HR vì đây là hệ thống internal, nên Admin sẽ tạo account cho HR. 
- **Reset Security:** Buộc đăng xuất tất cả thiết bị (thu hồi `refreshToken`), cấp lại mật khẩu.

## 2. Giám sát Luồng Xử lý & Phục hồi lỗi (Workflow & Fault Tolerance Monitoring)
Do nền tảng sử dụng RabbitMQ làm trung tâm điều phối các tác vụ nặng (Parse -> Embed -> Score), Admin cần có công cụ trực quan để theo dõi:
- **RabbitMQ Dashboard Tracker / Batch Monitor:**
    - Giao diện Admin kết nối với `recruitment-service` để hiển thị trạng thái của các Batch IDs (`POS{id}_{date}_B{uuid}`).
    - Xem thống kê real-time: Số lượng CV đang chờ xử lý, đang parse, đang chấm điểm, hoặc đã hoàn thành.
- **Quản lý Dead Letter Queue (DLQ):**
    - Khi LlamaParse timeout (>60s) hoặc LLM (Gemini) bị lỗi kết nối, message sẽ bị đẩy vào DLQ. Admin có thể xem danh sách các CV bị lỗi này.
    - **Tính năng thao tác:** Nút *"Retry Failed Jobs"* để đẩy các message từ DLQ trở lại Queue chính, hoặc bỏ qua/hủy bỏ các CV bị lỗi không thể phục hồi -> tôi nghĩ phần này không cần thiết vì đã có cơ chế retry và cleanup job rồi.

## 3. Cấu hình AI & Quản lý Chi phí (AI & System Configuration)
Do hệ thống phụ thuộc nhiều vào API của bên thứ 3 (LlamaParse, Gemini), Admin cần kiểm soát chặt chẽ ngân sách và hiệu năng:
- **Quản lý API Keys:** 
    - Giao diện nhập và thay đổi linh hoạt API Key cho **LlamaParse** và **Google Gemini** mà không cần khởi động lại container (có thể lưu config vào DB hoặc Cache).
- **Metric Tiêu thụ (Usage/Cost Metrics):**
    - Thống kê (ước lượng) số lượng token đã tiêu thụ, số lượng request API LlamaParse đã gọi trong tháng để tránh vượt quá giới hạn (Rate Limits / Billing quota).
    - Cảnh báo (Alerts) qua giao diện khi tài khoản Gemini gần hết quota -> đây sẽ là cảnh bảo cho HR thôi đúng không? Còn Candidate thì ta đâu thể alert họ được hả?
- **Cấu hình Trọng số Đánh giá (Dynamic AI Weights):**
    - Cho phép Admin thay đổi hệ số đo lường linh hoạt trên `ai-service`: `Core Fit (60%)`, `Experience (30%)`, `Qualification (10%)` hay `Match Score Threshold (>= 75)` thay vì hardcode.

## 4. Giám sát Hạ tầng, Dữ liệu & Lưu trữ (Infra & Storage Management)
Admin cần tổng quan về tình trạng lưu trữ do file PDF và Vector chiếm nhiều không gian:
- **Quản lý Storage (Google Drive):**
    - Hiển thị mức độ sử dụng dung lượng lưu trữ (Storage Quota).
    - **Dashboard Dọn dẹp Ngầm (Garbage Collection Job):** Trigger thủ công hoặc theo dõi tiến trình scheduler đang dọn dẹp các file rác (orphan files/deleted CVs) để tối ưu chi phí lưu trữ.
- **Giám sát Vector Database (Qdrant):**
    - Xem tổng số lượng collections, số lượng vector embbedings của CV và JD hiện có (`bge-small-en-v1.5`). Có chức năng "Re-index" nếu hệ thống bị trôi nhầm vector.
- **Microservices Health Check:**
    - Tích hợp ping status các dịch vụ: `api-gateway`, `auth-service`, `recruitment-service`, `ai-service`, `embedding-api`, `chatbot-service`. Cảnh báo ngay lập tức nếu một service bị "DOWN".

## 5. Báo cáo Tổng quan (System Analytics Dashboard)
Giao diện chính khi Admin đăng nhập:
- **Thống kê tổng quan:** Số lượng HR đang hoạt động, Tổng số ứng viên, Số lượng JD đang tuyển.
- **Lưu lượng hệ thống:** Biểu đồ thể hiện số khối lượng CV được upload và xử lý thành công theo ngày/tuần/tháng.
- **Time-to-Hire / Processing Time:** Metrics hiển thị thời gian trung bình để hệ thống xử lý xong một batch CV (từ lúc upload -> parsing -> scoring).

---

## Tác động Kiến trúc / Technical Implementation (Dành cho Developer)

Khi triển khai các ý tưởng trên, hệ thống cần được thay đổi ở các điểm sau:
1. **`auth-service`:** Thêm `ROLE_ADMIN` vào cơ chế JWT và định nghĩa quyền tạo super-user.
2. **`api-gateway`:** Cấu hình thêm routes bắt đầu bằng `/admin/**` và validate Authorization header phải chứa roles Admin.
3. **`common-library`:** Thêm các Admin DTOs, cấu trúc response thống kê dùng chung.
4. **`recruitment-service` / `ai-service`:** 
    - Expose các thiết endpoints REST API (ví dụ bằng Spring Boot Actuator) cho Health Check.
    - Xây dựng các Event liên quan tới thống kê, đếm số lượng bản ghi failed và success push vào Admin dashboard.
5. **Database:** Cần một bảng cấu hình (`system_configs`) để lưu trữ linh hoạt Trọng số AI và trạng thái bật/tắt các API dự phòng thay vì config tĩnh trong biến môi trường `.env`.
