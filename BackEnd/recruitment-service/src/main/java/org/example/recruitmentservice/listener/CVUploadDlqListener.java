package org.example.recruitmentservice.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.recruitmentservice.dto.request.CVUploadEvent;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.services.ProcessingBatchService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Listens exclusively to the CV upload Dead Letter Queue.
 * A message arrives here only after LlamaParseClient exhausts all retry attempts
 * and throws a terminal exception. At this point we surface the failure to the user via SSE.
 *
 * Intentionally separate from CVAnalysisResultListener to keep concerns isolated
 * — parsing failures vs scoring failures have different business impact.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CVUploadDlqListener {

    private final CandidateCVRepository candidateCVRepository;
    private final ProcessingBatchService processingBatchService;

    @RabbitListener(queues = RabbitMQConfig.CV_UPLOAD_DLQ)
    @Transactional
    public void handleParseFailure(@Payload CVUploadEvent event) {
        int cvId = event.getCvId();
        String batchId = event.getBatchId();

        log.error("[PARSE-DLQ] CV {} permanently failed parsing. batchId={}", cvId, batchId);

        candidateCVRepository.findById(cvId).ifPresent(cv -> {
            // Guard: do not overwrite if a concurrent thread already marked it FAILED
            if (cv.getCvStatus() == CVStatus.FAILED) {
                log.warn("[PARSE-DLQ] CV {} already marked FAILED, skipping duplicate DLQ processing", cvId);
                return;
            }
            markCvAsFailed(cv, "Parsing permanently failed after all retries. File may be corrupt or unsupported.");
            candidateCVRepository.save(cv);
        });

        // Update batch counters and push SSE notification to the waiting client
        processingBatchService.incrementProcessed(batchId, false);

        log.info("[PARSE-DLQ] Batch {} notified of CV {} parse failure via SSE", batchId, cvId);
    }

    private void markCvAsFailed(CandidateCV cv, String reason) {
        cv.setCvStatus(CVStatus.FAILED);
        cv.setErrorMessage(reason);
        cv.setFailedAt(LocalDateTime.now());
        cv.setUpdatedAt(LocalDateTime.now());
    }
}
