package org.example.recruitmentservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.dto.request.CreateSessionRequest;
import org.example.recruitmentservice.dto.request.FinalizeApplicationRequest;
import org.example.recruitmentservice.dto.request.InterviewNotificationRequest;
import org.example.recruitmentservice.dto.request.SaveMessageRequest;
import org.example.recruitmentservice.dto.response.*;
import org.example.recruitmentservice.dto.response.CvStatisticsResponse;
import org.example.recruitmentservice.services.ChatSessionService;
import org.example.recruitmentservice.services.ChatbotInternalService;
import org.example.recruitmentservice.services.FinalizeApplicationService;
import org.example.recruitmentservice.services.NotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller xử lý tất cả internal endpoints cho chatbot-service.
 * Bảo vệ bằng header X-Internal-Service — chỉ chatbot-service trên Docker network mới được gọi.
 *
 * Base path: /internal/chatbot
 */
@RestController
@RequestMapping("/internal/chatbot")
@RequiredArgsConstructor
public class ChatbotInternalController {

    private static final String INTERNAL_SERVICE_HEADER = "X-Internal-Service";
    private static final String EXPECTED_SERVICE_NAME   = "chatbot-service";

    @Value("${chatbot.internal-secret:chatbot-service}")
    private String internalSecret;

    private final ChatSessionService chatSessionService;
    private final ChatbotInternalService chatbotInternalService;
    private final FinalizeApplicationService finalizeApplicationService;
    private final NotificationService notificationService;

    // -------------------------------------------------------
    // Session Management
    // -------------------------------------------------------

    /** POST /internal/chatbot/session — Tạo chat session mới cho HR hoặc Candidate chatbot. */
    @PostMapping("/session")
    public ApiResponse<ChatSessionResponse> createSession(
            @RequestBody CreateSessionRequest request,
            HttpServletRequest httpRequest) {
        validateInternalRequest(httpRequest);
        ChatSessionResponse response = chatSessionService.createSession(request);
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Session created", response);
    }

    /** GET /internal/chatbot/session/{sessionId}/history?limit=20 — Sliding window history cho LLM context. */
    @GetMapping("/session/{sessionId}/history")
    public ApiResponse<List<ChatMessageResponse>> getSessionHistory(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest httpRequest) {
        validateInternalRequest(httpRequest);
        List<ChatMessageResponse> history = chatSessionService.getHistory(sessionId, limit);
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "History fetched", history);
    }

    /** POST /internal/chatbot/message — Persist một message turn vào chat history. */
    @PostMapping("/message")
    public ApiResponse<ChatMessageResponse> saveMessage(
            @RequestBody SaveMessageRequest request,
            HttpServletRequest httpRequest) {
        validateInternalRequest(httpRequest);
        ChatMessageResponse response = chatSessionService.saveMessage(request);
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Message saved", response);
    }

    // -------------------------------------------------------
    // Application & Workflow
    // -------------------------------------------------------

    /**
     * POST /internal/chatbot/finalize-application
     * Tạo Application CV (copy từ Master) + CVAnalysis từ chatbot scoring.
     */
    @PostMapping("/finalize-application")
    public ApiResponse<FinalizeApplicationResponse> finalizeApplication(
            @RequestBody FinalizeApplicationRequest request,
            HttpServletRequest httpRequest) {
        validateInternalRequest(httpRequest);
        FinalizeApplicationResponse response = finalizeApplicationService.finalizeApplication(request);
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Application finalized", response);
    }

    // -------------------------------------------------------
    // Data Retrieval
    // -------------------------------------------------------

    /** GET /internal/chatbot/positions/active — Danh sách positions đang active. */
    @GetMapping("/positions/active")
    public ApiResponse<List<ActivePositionResponse>> getActivePositions(HttpServletRequest httpRequest) {
        validateInternalRequest(httpRequest);
        List<ActivePositionResponse> positions = chatbotInternalService.getActivePositions();
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Active positions fetched", positions);
    }

    /**
     * POST /internal/chatbot/positions/details
     * Small-to-Big retrieval: chatbot-service sends positionIds extracted from Qdrant chunk hits
     * and receives back the full JD text for each, which is then fed to the scoring LLM.
     */
    @PostMapping("/positions/details")
    public ApiResponse<List<PositionDetailsResponse>> getPositionDetails(
            @RequestBody List<Integer> positionIds,
            HttpServletRequest httpRequest) {
        validateInternalRequest(httpRequest);
        List<PositionDetailsResponse> details = chatbotInternalService.getPositionDetails(positionIds);
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Position details fetched", details);
    }

    /**
     * GET /internal/chatbot/positions/{positionId}/cv-statistics
     * Thống kê số CV, số đã chấm, pass/fail cho HR chatbot.
     * Ngăn hallucination khi HR hỏi "Tôi đã upload bao nhiêu CV?".
     *
     * @param passThreshold ngưỡng pass, mặc định 75
     */
    @GetMapping("/positions/{positionId}/cv-statistics")
    public ApiResponse<CvStatisticsResponse> getCvStatistics(
            @PathVariable int positionId,
            @RequestParam(defaultValue = "75") int passThreshold,
            HttpServletRequest httpRequest) {
        validateInternalRequest(httpRequest);
        CvStatisticsResponse stats = chatbotInternalService.getCvStatistics(positionId, passThreshold);
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "CV statistics fetched", stats);
    }

    /**
     * GET /internal/chatbot/applications?positionId=X
     * Lấy danh sách ứng viên đã nộp đơn vào position — HR chatbot dùng để filter Qdrant.
     */
    @GetMapping("/applications")
    public ApiResponse<List<ApplicationSummaryResponse>> getApplicationsByPosition(
            @RequestParam int positionId,
            HttpServletRequest httpRequest) {
        validateInternalRequest(httpRequest);
        List<ApplicationSummaryResponse> applications = chatbotInternalService.getApplicationsByPosition(positionId);
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Applications fetched", applications);
    }

    // -------------------------------------------------------
    // Notification
    // -------------------------------------------------------

    /** POST /internal/chatbot/notify/interview — Gửi email phỏng vấn/offer/rejection qua SMTP. */
    @PostMapping("/notify/interview")
    public ApiResponse<Void> sendInterviewNotification(
            @RequestBody InterviewNotificationRequest request,
            HttpServletRequest httpRequest) {
        validateInternalRequest(httpRequest);
        notificationService.sendInterviewNotification(request);
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Email sent successfully");
    }

    // -------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------

    /**
     * Validate header X-Internal-Service để đảm bảo chỉ chatbot-service được gọi internal endpoints.
     */
    private void validateInternalRequest(HttpServletRequest request) {
        String serviceHeader = request.getHeader(INTERNAL_SERVICE_HEADER);
        if (!internalSecret.equals(serviceHeader) && !EXPECTED_SERVICE_NAME.equals(serviceHeader)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }
}
