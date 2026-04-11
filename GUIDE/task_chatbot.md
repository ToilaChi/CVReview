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
- [ ] ChatSessionService.java — business logic session/history
- [ ] FinalizeApplicationService.java — copy CV + create CVAnalysis
- [ ] NotificationService.java — gửi email SMTP
- [ ] ChatbotInternalController.java — 7 internal endpoints
- [ ] ChatbotPublicController.java — 2 public endpoints (FE chat history)
- [ ] Request/Response DTOs cho chatbot APIs
- [ ] PositionRepository — thêm query findAllActive()

## Phase 3: Candidate Chatbot (chatbot-service / Python)
- [ ] recruitment_api.py — HTTP client gọi internal APIs
- [ ] candidate_graph.py — refactor graph.py + session + scoring
- [ ] candidate_tools.py — evaluate_cv_fit, finalize_application
- [ ] candidate_chat.py — routes mới
- [ ] retriever.py — cập nhật JD filter + HR mode Candidate method
- [ ] config.py — thêm RECRUITMENT_SERVICE_URL, SCORE_THRESHOLD

## Phase 4: HR Chatbot (chatbot-service / Python)
- [ ] hr_graph.py — LangGraph mới cho HR
- [ ] hr_tools.py — get_candidate_details, send_interview_email
- [ ] hr_chat.py — routes mới
- [ ] main.py — đăng ký routers mới
