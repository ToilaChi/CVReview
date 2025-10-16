package org.example.recruitmentservice.services;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.commonlibrary.dto.request.CVAnalysisRequest;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.models.entity.Positions;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.PositionRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {
    private final RabbitTemplate rabbitTemplate;
    private final CandidateCVRepository candidateCVRepository;
    private final PositionRepository positionRepository;

    @Value("${analysis.batch-size}")
    private int batchSize;

    public ApiResponse<String> analyzeCvs(Integer positionId, List<Integer> cvIds) {
        Positions position = positionRepository.findById(positionId)
                .orElseThrow(() -> new CustomException(ErrorCode.POSITION_NOT_FOUND));
        String jdText = position.getJobDescription();

        List<CandidateCV> cvs;

        if(cvIds == null || cvIds.isEmpty()) {
            cvs = candidateCVRepository.findByPositionIdAndCvStatus(positionId, CVStatus.PARSED);
            log.info("[AI_ANALYSIS] Auto-select all CVs of Position {} -> total {}", positionId, cvs.size());
        } else {
            cvs = candidateCVRepository.findAllById(cvIds);
        }

        if (cvs.isEmpty()) {
            throw new CustomException(ErrorCode.CV_NOT_FOUND);
        }

        String batchId = "POS" + positionId + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        log.info("[AI_ANALYSIS] Start sending {} CV(s) for Position {} | batchId={}",
                cvs.size(), positionId, batchId);

        List<List<CandidateCV>> batches = partitionList(cvs, batchSize);

        int batchIndex = 0;
        for (List<CandidateCV> batch : batches) {
            batchIndex++;
            String subBatchId = batchId + "_B" + batchIndex;

            for (CandidateCV cv : batch) {
                if (cv.getCvContent() == null || cv.getCvContent().isEmpty()) {
                    log.warn("[AI_ANALYSIS] Skip CV {} (no parsed content)", cv.getId());
                    continue;
                }

                cv.setCvStatus(CVStatus.SCORING);
                cv.setUpdatedAt(LocalDateTime.now());
                candidateCVRepository.save(cv);

                CVAnalysisRequest request = new CVAnalysisRequest();
                request.setCvId(cv.getId());
                request.setPositionId(positionId);
                request.setCvText(cv.getCvContent());
                request.setJdText(jdText);
                request.setBatchId(subBatchId);

                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.AI_EXCHANGE,
                        RabbitMQConfig.CV_ANALYZE_ROUTING_KEY,
                        request
                );

                log.info("[AI_ANALYSIS] Sent CV {} to queue (batch={})", cv.getId(), subBatchId);
            }
        }

        log.info("[AI_ANALYSIS] Completed publishing CV batch for Position {} | batchId={}", positionId, batchId);
        return new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                "Please wait a moment!",
                batchId,
                LocalDateTime.now()
                );
    }

    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return parts;
    }
}
