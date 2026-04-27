package org.example.recruitmentservice.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.recruitmentservice.dto.request.EmbedReplyEvent;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.services.ProcessingBatchService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Stage 2 của Two-Stage Pipeline — reply handler.
 *
 * Lắng nghe kết quả trả về từ embedding-service sau khi upsert Qdrant.
 * Cập nhật trạng thái DB thành EMBEDDED (thành công) hoặc FAILED (thất bại).
 * Dùng rabbitListenerContainerFactory mặc định vì đây là operation nhanh (chỉ write DB).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbedReplyListener {

    private final CandidateCVRepository candidateCVRepository;
    private final ProcessingBatchService processingBatchService;

    @RabbitListener(queues = RabbitMQConfig.CV_EMBED_REPLY_QUEUE)
    @Transactional
    public void handleEmbedReply(@Payload EmbedReplyEvent event) {
        int cvId = event.getCvId();
        log.info("[EMBED-REPLY] Received reply for cvId={} | success={}", cvId, event.isSuccess());

        CandidateCV cv = candidateCVRepository.findById(cvId)
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));

        if (event.isSuccess()) {
            cv.setCvStatus(CVStatus.EMBEDDED);
            cv.setUpdatedAt(LocalDateTime.now());
            cv.setErrorMessage(null);
            cv.setFailedAt(null);
            candidateCVRepository.save(cv);

            processingBatchService.incrementProcessed(event.getBatchId(), true);
            log.info("[EMBED-REPLY] CV {} successfully embedded into Qdrant.", cvId);
        } else {
            cv.setCvStatus(CVStatus.FAILED);
            cv.setErrorMessage(truncate(event.getErrorMessage()));
            cv.setFailedAt(LocalDateTime.now());
            cv.setUpdatedAt(LocalDateTime.now());
            candidateCVRepository.save(cv);

            processingBatchService.incrementProcessed(event.getBatchId(), false);
            log.error("[EMBED-REPLY] Embedding failed for cvId={}: {}", cvId, event.getErrorMessage());
        }
    }

    private String truncate(String message) {
        if (message == null) return "Unknown embedding error";
        return message.length() > 1000 ? message.substring(0, 1000) + "... [truncated]" : message;
    }
}
