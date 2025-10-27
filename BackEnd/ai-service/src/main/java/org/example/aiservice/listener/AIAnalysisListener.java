package org.example.aiservice.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aiservice.config.RabbitMQConfig;
import org.example.aiservice.services.LlmAnalysisService;
import org.example.commonlibrary.dto.request.CVAnalysisRequest;
import org.example.commonlibrary.dto.response.CVAnalysisResult;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class AIAnalysisListener {
    private final LlmAnalysisService llmAnalysisService;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = RabbitMQConfig.CV_ANALYZE_QUEUE, containerFactory = "rabbitListenerContainerFactory")
    public void handleAnalyzeRequest(@Payload CVAnalysisRequest request,
                                     @Header(value = "x-retry-count", required = false) Integer retryCount) {

        if (retryCount == null) retryCount = 0;

        log.info("[AI-LISTENER] Processing cvId={}, retry attempt={}/3",
                request.getCvId(), retryCount);

        try {
            validateRequest(request, request.getCvId(), request.getBatchId());
            CVAnalysisResult result = llmAnalysisService.analyze(request);

            // Success → publish result
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.AI_EXCHANGE,
                    RabbitMQConfig.CV_ANALYSIS_RESULT_ROUTING_KEY,
                    result
            );


            log.info("[AI-LISTENER] Successfully published analysis result: cvId={}, batchId={}, score={}",
                    request.getCvId(), request.getBatchId(), result.getScore());

        } catch (Exception e) {
            log.error("[AI-LISTENER] Analysis failed for cvId={}, retry={}, error={}",
                    request.getCvId(), retryCount, e.getMessage());

            throw e; // Re-throw để trigger retry
        }
    }

    // Helper method

    private void validateRequest(CVAnalysisRequest request, Integer cvId, String batchId) {
        if (request.getCvText() == null || request.getCvText().isBlank()) {
            log.error("[AI-LISTENER] Validation failed: cvText is empty for cvId={}, batchId={}",
                    cvId, batchId);
            throw new IllegalArgumentException("cvText is empty or null");
        }

        if (request.getJdText() == null || request.getJdText().isBlank()) {
            log.error("[AI-LISTENER] Validation failed: jdText is empty for cvId={}, batchId={}",
                    cvId, batchId);
            throw new IllegalArgumentException("jdText is empty or null");
        }

        if (cvId == null || cvId <= 0) {
            log.error("[AI-LISTENER] Validation failed: invalid cvId={}", cvId);
            throw new IllegalArgumentException("cvId is invalid");
        }

        if (batchId == null || batchId.isBlank()) {
            log.error("[AI-LISTENER] Validation failed: batchId is empty");
            throw new IllegalArgumentException("batchId is empty or null");
        }
    }

    private String determineErrorType(Exception ex) {
        String message = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";

        if (message.contains("quota exceeded") || message.contains("rate limit")) {
            return "QUOTA_EXCEEDED";
        } else if (message.contains("timeout")) {
            return "TIMEOUT";
        } else if (message.contains("parse") || message.contains("json")) {
            return "PARSE_ERROR";
        } else if (message.contains("connection") || message.contains("network")) {
            return "NETWORK_ERROR";
        } else if (ex instanceof IllegalArgumentException) {
            return "VALIDATION_ERROR";
        } else {
            return "UNKNOWN_ERROR";
        }
    }
}
