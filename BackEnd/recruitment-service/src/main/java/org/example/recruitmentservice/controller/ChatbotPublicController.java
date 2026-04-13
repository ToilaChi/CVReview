package org.example.recruitmentservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.dto.response.PageResponse;
import org.example.commonlibrary.exception.CustomException;
import org.example.commonlibrary.utils.PageUtil;
import org.example.recruitmentservice.dto.response.ChatMessageResponse;
import org.example.recruitmentservice.dto.response.ChatSessionResponse;
import org.example.recruitmentservice.services.ChatSessionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Public endpoints cho FE xem lại lịch sử chat.
 * Đi qua API Gateway (JWT required) — dùng header X-User-Id inject bởi gateway.
 *
 * Base path: /api/chatbot
 */
@RestController
@RequestMapping("/api/chatbot")
@RequiredArgsConstructor
public class ChatbotPublicController {

    private final ChatSessionService chatSessionService;

    /**
     * GET /api/chatbot/sessions — Danh sách sessions của user hiện tại.
     * Sắp xếp theo lastActiveAt DESC — session mới nhất lên đầu.
     */
    @PreAuthorize("hasAnyRole('HR', 'CANDIDATE')")
    @GetMapping("/sessions")
    public ApiResponse<PageResponse<ChatSessionResponse>> getUserSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest request) {
        String userId = extractUserId(request);
        Pageable pageable = PageRequest.of(page, size);
        Page<ChatSessionResponse> sessions = chatSessionService.getUserSessions(userId, pageable);
        return ApiResponse.<PageResponse<ChatSessionResponse>>builder()
                .statusCode(ErrorCode.SUCCESS.getCode())
                .message("Sessions fetched successfully")
                .data(PageUtil.toPageResponse(sessions))
                .build();
    }

    /**
     * GET /api/chatbot/sessions/{sessionId} — Full chat history của 1 session.
     * FE dùng để render lại toàn bộ cuộc hội thoại khi user click vào session cũ.
     */
    @PreAuthorize("hasAnyRole('HR', 'CANDIDATE')")
    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<List<ChatMessageResponse>> getSessionHistory(
            @PathVariable String sessionId,
            HttpServletRequest request) {
        // userId dùng để log audit, không cần dùng trực tiếp vì sessionId đã là unique key
        extractUserId(request);
        List<ChatMessageResponse> history = chatSessionService.getFullHistory(sessionId);
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "History fetched successfully", history);
    }

    private String extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null || userId.isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_ACTION);
        }
        return userId;
    }
}
