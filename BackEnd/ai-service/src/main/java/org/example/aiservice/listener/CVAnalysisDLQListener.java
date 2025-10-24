package org.example.aiservice.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aiservice.config.RabbitMQConfig;
import org.example.commonlibrary.dto.request.CVAnalysisRequest;
import org.example.commonlibrary.dto.response.CVAnalysisFailure;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class CVAnalysisDLQListener {
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = RabbitMQConfig.CV_ANALYZE_DLQ, containerFactory = "rabbitListenerContainerFactory")
    public void handleDLQMessage(@Payload CVAnalysisRequest request, Message message) {
        log.error("=== DLQ MESSAGE RECEIVED ===");
        log.error("CV ID: {}", request.getCvId());
        log.error("Batch ID: {}", request.getBatchId());

        try {
            String errorMessage = extractErrorMessage(message);

            log.error("Error Message: {}", errorMessage);
            log.error("Original Request: cvId={}, batchId={}, positionId={}",
                    request.getCvId(), request.getBatchId(), request.getPositionId());

            // Create failure event
            CVAnalysisFailure failureEvent = CVAnalysisFailure.builder()
                    .cvId(request.getCvId())
                    .batchId(request.getBatchId())
                    .positionId(request.getPositionId())
                    .errorMessage(errorMessage)
                    .failedAt(LocalDateTime.now())
                    .retryCount(3)
                    .build();

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.AI_EXCHANGE,
                    RabbitMQConfig.CV_ANALYSIS_FAILED_ROUTING_KEY,
                    failureEvent
            );
            log.info("[DLQ-LISTENER] Published failure event for cvId={} batchId={}",
                    request.getCvId(), request.getBatchId());

        } catch (Exception e) {
            log.error("CRITICAL: Failed to process DLQ message for CV {}: {}",
                    request.getCvId(), e.getMessage(), e);
        }
    }

    // Helper method

    private String extractErrorMessage(Message message) {
        StringBuilder errorBuilder = new StringBuilder();

        // Try x-exception-message header
        Object exceptionMessage = message.getMessageProperties()
                .getHeaders().get("x-exception-message");
        if (exceptionMessage != null) {
            errorBuilder.append(exceptionMessage);
        }

        // Try x-exception-stacktrace header (truncate to 500 chars)
        Object exceptionStacktrace = message.getMessageProperties()
                .getHeaders().get("x-exception-stacktrace");
        if (exceptionStacktrace != null && errorBuilder.isEmpty()) {
            String stacktrace = exceptionStacktrace.toString();
            errorBuilder.append(stacktrace, 0, Math.min(stacktrace.length(), 500));
        }

        // Default message
        if (errorBuilder.isEmpty()) {
            errorBuilder.append("CV analysis failed after 3 retry attempts. Gemini API may be rate-limited or unavailable.");
        }

        return errorBuilder.toString();
    }
}
