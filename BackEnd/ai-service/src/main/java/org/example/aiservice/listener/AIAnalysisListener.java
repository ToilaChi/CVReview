package org.example.aiservice.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.aiservice.config.RabbitMQConfig;
import org.example.aiservice.services.LlmAnalysisService;
import org.example.commonlibrary.dto.request.CVAnalysisRequest;
import org.example.commonlibrary.dto.response.CVAnalysisResult;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    @Retryable(
//            value = {RuntimeException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2, maxDelay = 10000)
//            exclude = {IllegalArgumentException.class}
    )
    public void handleAnalyzeRequest(@Payload CVAnalysisRequest request) {
        String batchId = request.getBatchId();
        Integer cvId = request.getCvId();

        log.info("[AI-LISTENER] Received analyze request. batchId={} cvId={} positionId={}",
                batchId, cvId, request.getPositionId());

        // Basic validation
        if (request.getCvText() == null || request.getCvText().isBlank()) {
            log.warn("[AI-LISTENER] cvText empty for cvId={} batchId={}, throwing to trigger retry/DLQ", cvId, batchId);
            throw new IllegalArgumentException("cvText is empty");
        }
        if (request.getJdText() == null || request.getJdText().isBlank()) {
            log.warn("[AI-LISTENER] jdText empty for cvId={} batchId={}, throwing to trigger retry/DLQ", cvId, batchId);
            throw new IllegalArgumentException("jdText is empty");
        }

        try {
            CVAnalysisResult result = llmAnalysisService.analyze(request);

            // Set analyzedAt if not set by LLM service
            if (result.getAnalyzedAt() == null) {
                result.setAnalyzedAt(LocalDateTime.now());
            }

            // Publish result back to recruitment-service via AI exchange -> routing key cv.analysis.result
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.AI_EXCHANGE,
                    RabbitMQConfig.CV_ANALYSIS_RESULT_ROUTING_KEY,
                    result
            );

            log.info("[AI-LISTENER] Published analysis result. cvId={} batchId={} score={}",
                    cvId, batchId, result.getScore());

        } catch (Exception ex) {
            // Any runtime exception -> allow retry template configured in container factory to handle retries.
            // After retries exhausted, because factory.setDefaultRequeueRejected(false), message will be rejected and sent to DLQ.
            log.error("[AI-LISTENER] Error processing cvId={} batchId={} -> will be retried or moved to DLQ. cause={}",
                    cvId, batchId, ex.getMessage(), ex);
            throw new RuntimeException("LLM analysis failed", ex);
        }
    }
}
