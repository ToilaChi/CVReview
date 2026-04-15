# Chatbot Feature — Task Tracker

## Phase 1: Database & Entity (recruitment-service / Java)
- [x] Positions.java — thêm `isActive`, `openedAt`, `closedAt`
- [x] CandidateCV.java — thêm `parentCvId`, xóa unique constraint `candidateId`
- [x] ChatbotType enum — mới
- [x] ChatMode enum — mới
- [x] ChatRole enum — mới
- [x] ChatSession.java — entity mới
- [x] ChatHistory.java — entity mới
- [x] ChatSessionRepository.java — repository mới
- [x] ChatHistoryRepository.java — repository mới
- [x] UploadCVService.java — sửa duplicate check + thêm re-upload soft-delete logic
- [x] CandidateCVRepository.java — thêm 4 queries chatbot
- [x] PositionRepository.java — thêm findAllActive()

## Phase 2: Internal & Public APIs (recruitment-service / Java)
- [x] ChatSessionService.java — business logic session/history
- [x] FinalizeApplicationService.java — copy CV + create CVAnalysis
- [x] NotificationService.java — gửi email SMTP (Spring Mail + Thymeleaf)
- [x] ChatbotInternalController.java — 7 internal endpoints
- [x] ChatbotPublicController.java — 2 public endpoints (FE chat history)
- [x] Request/Response DTOs cho chatbot APIs (6 DTOs)
- [x] EmailType enum — INTERVIEW_INVITE, OFFER_LETTER, REJECTION
- [x] Email HTML templates (Thymeleaf) — interview-invite, offer-letter, rejection
- [x] ErrorCode — thêm chatbot error codes (8001–8005)
- [x] pom.xml — thêm spring-boot-starter-mail + thymeleaf
- [x] application-local.yml — thêm mail SMTP config + chatbot internal secret

## Phase 3: Candidate Chatbot (chatbot-service / Python)
- [x] recruitment_api.py — HTTP client gọi internal APIs
- [x] candidate_graph.py — refactor graph.py + session + scoring
- [x] candidate_tools.py — evaluate_cv_fit, finalize_application
- [x] candidate_chat.py — routes mới
- [x] retriever.py — cập nhật JD filter + HR mode Candidate method
- [x] config.py — thêm RECRUITMENT_SERVICE_URL, SCORE_THRESHOLD

## Phase 4: HR Chatbot (chatbot-service / Python)
- [x] hr_tools.py — get_candidate_details, send_interview_email
- [x] hr_graph.py — LangGraph mới cho HR (7 nodes, dual-mode)
- [x] hr_chat.py — routes mới (POST /hr/session, POST /hr/chat)
- [x] main.py — đăng ký routers mới (candidate_chat + hr_chat)
- [x] retriever.py — thêm retrieve_for_hr_mode (HR_MODE Qdrant filter)
- [x] recruitment_api.py — thêm get_candidate_details + send_interview_email
- [x] api/routes/__init__.py — export candidate_chat + hr_chat

