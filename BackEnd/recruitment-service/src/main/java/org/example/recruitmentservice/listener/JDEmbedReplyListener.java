package org.example.recruitmentservice.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.recruitmentservice.dto.request.EmbedReplyEvent;
import org.example.recruitmentservice.models.entity.Positions;
import org.example.recruitmentservice.models.enums.JDStatus;
import org.example.recruitmentservice.repository.PositionRepository;
import org.example.recruitmentservice.services.ProcessingBatchService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class JDEmbedReplyListener {

    private final PositionRepository positionRepository;
    private final ProcessingBatchService processingBatchService;

    @RabbitListener(queues = RabbitMQConfig.JD_EMBED_REPLY_QUEUE)
    @Transactional
    public void handleEmbedReply(@Payload EmbedReplyEvent event) {
        int positionId = event.getCvId(); // Reusing cvId field from EmbedReplyEvent for positionId
        log.info("[JD-EMBED-REPLY] Received reply for positionId={} | success={}", positionId, event.isSuccess());

        Positions position = positionRepository.findById(positionId);
        if (position == null) {
            throw new CustomException(ErrorCode.POSITION_NOT_FOUND);
        }

        if (event.isSuccess()) {
            position.setStatus(JDStatus.EMBEDDED);
            position.setUpdatedAt(LocalDateTime.now());
            position.setErrorMessage(null);
            positionRepository.save(position);

            processingBatchService.incrementProcessed(event.getBatchId(), true);
            log.info("[JD-EMBED-REPLY] Position {} successfully embedded into Qdrant.", positionId);
        } else {
            position.setStatus(JDStatus.FAILED);
            position.setErrorMessage(truncate(event.getErrorMessage()));
            position.setUpdatedAt(LocalDateTime.now());
            positionRepository.save(position);

            processingBatchService.incrementProcessed(event.getBatchId(), false);
            log.error("[JD-EMBED-REPLY] Embedding failed for positionId={}: {}", positionId, event.getErrorMessage());
        }
    }

    private String truncate(String message) {
        if (message == null) return "Unknown embedding error";
        return message.length() > 1000 ? message.substring(0, 1000) + "... [truncated]" : message;
    }
}
