package org.example.recruitmentservice.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.recruitmentservice.dto.request.CVExtractedEvent;
import org.example.recruitmentservice.dto.request.CVUploadEvent;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.services.metadata.GeminiExtractionService;
import org.example.recruitmentservice.services.metadata.model.CVMetadata;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Stage 1 của Two-Stage Pipeline.
 *
 * Nhận CV đã parse (status=EXTRACTED), gọi Gemini để extract metadata JSON,
 * sau đó publish CVExtractedEvent sang cv.embed.queue để embedding-service xử lý.
 *
 * Dùng containerFactory riêng (cvExtractionContainerFactory) với concurrency thấp
 * để tránh bị throttle bởi Gemini API rate limit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractCVListener {

    private final CandidateCVRepository candidateCVRepository;
    private final GeminiExtractionService geminiExtractionService;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = RabbitMQConfig.CV_EXTRACT_QUEUE, containerFactory = "cvExtractionContainerFactory")
    @Transactional
    public void handleExtract(@Payload CVUploadEvent event) {
        int cvId = event.getCvId();
        log.info("[EXTRACT] Received extraction trigger for cvId={}", cvId);

        CandidateCV cv = candidateCVRepository.findById(cvId)
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));

        // Guard: skip CVs that already failed or were re-queued stale
        if (cv.getCvStatus() == CVStatus.FAILED || cv.getCvStatus() == CVStatus.EMBEDDED) {
            log.warn("[EXTRACT] CV {} in terminal state {}, discarding stale message", cvId, cv.getCvStatus());
            return;
        }

        if (cv.getCvContent() == null || cv.getCvContent().isBlank()) {
            log.error("[EXTRACT] CV {} has no parsed content, cannot extract metadata", cvId);
            markAsFailed(cv, "CV content is empty — cannot extract metadata.");
            throw new RuntimeException("CV content missing for cvId=" + cvId);
        }

        try {
            cv.setCvStatus(CVStatus.EMBEDDING);
            cv.setUpdatedAt(LocalDateTime.now());
            candidateCVRepository.save(cv);

            CVMetadata metadata = geminiExtractionService.extractMetadata(cv.getCvContent());

            CVExtractedEvent extractedEvent = CVExtractedEvent.builder()
                    .cvId(cvId)
                    .cvText(cv.getCvContent())
                    .metadata(metadata)
                    .positionId(event.getPositionId())
                    .batchId(event.getBatchId())
                    .build();

            rabbitTemplate.convertAndSend(RabbitMQConfig.CV_CHUNKED_QUEUE, extractedEvent);
            log.info("[EXTRACT] Published CVExtractedEvent for cvId={} to cv.embed.queue", cvId);

        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[EXTRACT] Metadata extraction failed for cvId={}: {}", cvId, e.getMessage(), e);
            // Re-throw để RabbitMQ route sang cv.extract.queue.dlq
            throw new RuntimeException("Metadata extraction failed for cvId=" + cvId, e);
        }
    }

    private void markAsFailed(CandidateCV cv, String reason) {
        cv.setCvStatus(CVStatus.FAILED);
        cv.setErrorMessage(reason);
        cv.setFailedAt(LocalDateTime.now());
        cv.setUpdatedAt(LocalDateTime.now());
        candidateCVRepository.save(cv);
    }
}
