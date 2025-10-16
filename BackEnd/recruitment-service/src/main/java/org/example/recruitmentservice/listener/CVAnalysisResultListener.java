package org.example.recruitmentservice.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonlibrary.dto.response.CVAnalysisResult;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.recruitmentservice.models.entity.CVAnalysis;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.models.entity.Positions;
import org.example.recruitmentservice.repository.CVAnalysisRepository;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.PositionRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class CVAnalysisResultListener {
    private final CVAnalysisRepository cvAnalysisRepository;
    private final CandidateCVRepository candidateCVRepository;
    private final PositionRepository positionRepository;

    @RabbitListener(queues = RabbitMQConfig.CV_ANALYSIS_RESULT_QUEUE)
    public void handleAnalysisResult(@Payload CVAnalysisResult result) {
        log.info("[AI-RESULT] Received analysis result for cvId={} score={} batchId={}",
                result.getCvId(), result.getScore(), result.getBatchId());

        try {
            CandidateCV cv = candidateCVRepository.findById(result.getCvId())
                    .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));

            Positions positions = positionRepository.findByCandidateCVId(result.getCvId());

            CVAnalysis cvAnalysis = new CVAnalysis();
            cvAnalysis.setCandidateCV(cv);
            cvAnalysis.setPositionId(positions.getId());
            cvAnalysis.setPositionName(positions.getName() + " " + positions.getLanguage() + " " + positions.getLevel());
            cvAnalysis.setScore(result.getScore());
            cvAnalysis.setFeedback(result.getFeedback());
            cvAnalysis.setSkillMatch(String.join(", ", result.getSkillMatch()));
            cvAnalysis.setSkillMiss(String.join(", ", result.getSkillMiss()));
            cvAnalysis.setAnalyzedAt(result.getAnalyzedAt() != null ? result.getAnalyzedAt() : LocalDateTime.now());
            cvAnalysisRepository.save(cvAnalysis);

            cv.setCvStatus(CVStatus.SCORED);
            candidateCVRepository.save(cv);

            log.info("[AI-RESULT] Saved analysis result for cvId={} score={}", result.getCvId(), result.getScore());
        } catch (Exception e) {
            log.error("[AI-RESULT] Error saving analysis result for cvId={} | cause={}",
                    result.getCvId(), e.getMessage(), e);
            throw e;
        }
    }
}
