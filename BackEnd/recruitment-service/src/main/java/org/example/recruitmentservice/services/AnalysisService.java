package org.example.recruitmentservice.services;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.CVAnalysisFailure;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.config.RabbitMQConfig;
import org.example.commonlibrary.dto.request.CVAnalysisRequest;
import org.example.recruitmentservice.dto.request.ManualScoreRequest;
import org.example.recruitmentservice.dto.response.BatchRetryResponse;
import org.example.recruitmentservice.dto.response.CandidateCVResponse;
import org.example.recruitmentservice.models.entity.CVAnalysis;
import org.example.recruitmentservice.models.entity.ProcessingBatch;
import org.example.recruitmentservice.models.enums.BatchStatus;
import org.example.recruitmentservice.models.enums.BatchType;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.models.entity.Positions;
import org.example.recruitmentservice.repository.CVAnalysisRepository;
import org.example.recruitmentservice.repository.ProcessingBatchRepository;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.PositionRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {
    private final RabbitTemplate rabbitTemplate;
    private final CandidateCVRepository candidateCVRepository;
    private final PositionRepository positionRepository;
    private final ProcessingBatchRepository processingBatchRepository;
    private final CVAnalysisRepository cvAnalysisRepository;
    private final ProcessingBatchService processingBatchService;

    @Value("${analysis.batch-size}")
    private int batchSize;

    public ApiResponse<Map<String, Object>> analyzeCvs(Integer positionId, List<Integer> cvIds) {
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

        ProcessingBatch processingBatch = processingBatchService.createBatch(
                batchId,
                positionId,
                cvs.size(),
                BatchType.SCORING
        );

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
                cv.setBatchId(batchId);
                candidateCVRepository.save(cv);

                CVAnalysisRequest request = new CVAnalysisRequest();
                request.setCvId(cv.getId());
                request.setPositionId(positionId);
                request.setCvText(cv.getCvContent());
                request.setJdText(jdText);
                request.setBatchId(batchId);

                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.AI_EXCHANGE,
                        RabbitMQConfig.CV_ANALYZE_ROUTING_KEY,
                        request
                );

                log.info("[AI_ANALYSIS] Sent CV {} to queue (batch={})", cv.getId(), subBatchId);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("batchId", batchId);
        response.put("message", "Please wait a moment. Your CVs are being processed.");
        response.put("totalCv", cvs.size());
        assert processingBatch != null;
        response.put("status", processingBatch.getStatus());

        log.info("[AI_ANALYSIS] Completed publishing CV batch for Position {} | batchId={}", positionId, batchId);
        return new ApiResponse<>(200, "Batch created successfully", response);
    }

    @Transactional
    public ApiResponse<BatchRetryResponse> retryFailedCVsInBatch(String batchId) {
        log.info("[RETRY-BATCH] Starting retry for failed CVs in batch: {}", batchId);

        ProcessingBatch batch = processingBatchRepository.findByBatchId(batchId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATCH_NOT_FOUND));

        List<CandidateCV> failedCVs = candidateCVRepository
                .findByPositionIdAndCvStatus(batch.getPositionId(), CVStatus.FAILED);

        if (failedCVs.isEmpty()) {
            log.warn("[RETRY-BATCH] No failed CVs found in batch: {}", batchId);
            throw new CustomException(ErrorCode.NO_FAILED_CVS_IN_BATCH);
        }

        log.info("[RETRY-BATCH] Found {} failed CVs to retry", failedCVs.size());

        int successCount = 0;
        int failCount = 0;
        List<Integer> retriedCvIds = failedCVs.stream()
                .map(CandidateCV::getId)
                .collect(Collectors.toList());

        for (CandidateCV cv : failedCVs) {
            try {
                // Reset CV status
                cv.setCvStatus(CVStatus.SCORING);
                cv.setRetryCount(0);
                cv.setErrorMessage(null);
                cv.setFailedAt(null);
                cv.setUpdatedAt(LocalDateTime.now());
                candidateCVRepository.save(cv);

                CVAnalysisRequest request = CVAnalysisRequest.builder()
                        .cvId(cv.getId())
                        .batchId(batchId)
                        .positionId(cv.getPosition().getId())
                        .cvText(cv.getCvContent())
                        .jdText(cv.getPosition().getJobDescription())
                        .build();

                // Republish to RabbitMQ
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.AI_EXCHANGE,
                        RabbitMQConfig.CV_ANALYZE_ROUTING_KEY,
                        request);

                log.info("[RETRY-BATCH] Successfully queued CV {} for retry", cv.getId());
                successCount++;

            } catch (Exception e) {
                log.error("[RETRY-BATCH] Failed to queue CV {} for retry: {}",
                        cv.getId(), e.getMessage(), e);

                // Rollback this CV
                cv.setCvStatus(CVStatus.FAILED);
                cv.setErrorMessage("Retry failed: " + e.getMessage());
                candidateCVRepository.save(cv);
                failCount++;
            }
        }

        batch.setFailedCv(batch.getFailedCv() - successCount);
        batch.setProcessedCv(batch.getProcessedCv() - successCount);

        // If batch was COMPLETED, revert to PROCESSING
        if (batch.getStatus() == BatchStatus.COMPLETED) {
            batch.setStatus(BatchStatus.PROCESSING);
            batch.setCompletedAt(null);
        }

        batch.setCreatedAt(LocalDateTime.now());
        processingBatchRepository.save(batch);

        log.info("[RETRY-BATCH] Batch {} retry completed: {} success, {} failed",
                batchId, successCount, failCount);

        BatchRetryResponse response = BatchRetryResponse.builder()
                .batchId(batchId)
                .totalRetried(successCount)
                .failedToRetry(failCount)
                .retriedCvIds(retriedCvIds)
                .message(String.format("Queued %d CVs for retry", successCount))
                .build();

        return new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                "The retry request was sent successfully. Please wait a moment.",
                response
        );
    }

    @Transactional
    public ApiResponse<CandidateCVResponse> retrySingleCV(Integer cvId) {
        CandidateCV cv = candidateCVRepository.findById(cvId)
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));

        // Validate CV status
        if (cv.getCvStatus() == CVStatus.SCORING) {
            throw new CustomException(ErrorCode.CV_ALREADY_PROCESSING);
        }

        if (cv.getCvStatus() != CVStatus.FAILED) {
            throw new CustomException(ErrorCode.CV_NOT_FAILED);
        }

        String batchId = cv.getBatchId();

        // Reset CV status for retry
        cv.setCvStatus(CVStatus.SCORING);
        cv.setRetryCount(0);
        cv.setErrorMessage(null);
        cv.setFailedAt(null);
        cv.setUpdatedAt(LocalDateTime.now());

        candidateCVRepository.save(cv);
        log.info("Reset CV {} status to SCORING for single retry", cvId);

        // Create new analysis request
        CVAnalysisRequest request = CVAnalysisRequest.builder()
                .cvId(cv.getId())
                .batchId(batchId)
                .positionId(cv.getPosition().getId())
                .cvText(cv.getCvContent())
                .jdText(cv.getPosition().getJobDescription())
                .build();

        // Republish to RabbitMQ
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.AI_EXCHANGE,
                    RabbitMQConfig.CV_ANALYZE_ROUTING_KEY,
                    request);
            log.info("Republished CV {} to analysis queue for single retry", cvId);

            // Update batch counters
            updateBatchCountersForRetry(batchId);

        } catch (Exception e) {
            log.error("Failed to republish CV {} to queue: {}", cvId, e.getMessage());
            // Rollback CV status
            cv.setCvStatus(CVStatus.FAILED);
            candidateCVRepository.save(cv);
            throw new CustomException(ErrorCode.RETRY_FAILED);
        }

        return new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                "CV queued for retry successfully",
                toResponse(cv)
        );
    }

    @Transactional
    public ApiResponse<CandidateCVResponse>  manualScore(Integer cvId, ManualScoreRequest request) {
        CandidateCV cv = candidateCVRepository.findById(cvId)
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));

        // Validate: Allow manual scoring for FAILED or with confirmation for SCORED
        if (cv.getCvStatus() == CVStatus.SCORING) {
            throw new CustomException(ErrorCode.CV_ALREADY_PROCESSING);
        }

        boolean wasFailed = cv.getCvStatus() == CVStatus.FAILED;

        // Update CV status
        cv.setCvStatus(CVStatus.SCORED);
        cv.setScoredAt(LocalDateTime.now());
        cv.setUpdatedAt(LocalDateTime.now());

        // Clear error info if previously failed
        if (wasFailed) {
            cv.setErrorMessage(null);
            cv.setFailedAt(null);

            // Update batch: move from failed to success
            String batchId = cv.getBatchId();
            updateBatchCountersForManualScore(batchId);
        }

        candidateCVRepository.save(cv);
        log.info("Updated CV {} status to SCORED (manual)", cvId);

        // Create or update CVAnalysis
        CVAnalysis analysis = cvAnalysisRepository.findByCandidateCV_Id(cvId)
                .orElse(new CVAnalysis());

        analysis.setCandidateCV(cv);
        analysis.setScore(request.getScore());
        analysis.setFeedback(request.getFeedback());
        analysis.setSkillMatch(String.join(",", request.getSkillMatch()));
        analysis.setSkillMiss(String.join(",", request.getSkillMiss()));
        analysis.setAnalysisMethod("MANUAL");
        analysis.setAnalyzedAt(LocalDateTime.now());

        cvAnalysisRepository.save(analysis);
        log.info("Saved manual analysis for CV {}: score={}", cvId, request.getScore());

        return new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                "CV scored manually successfully",
                toResponse(cv)
        );
    }

    @Transactional
    public void handleFailedCV(CVAnalysisFailure failure) {
        log.info("Processing failed CV {} for batch {}", failure.getCvId(), failure.getBatchId());

        CandidateCV cv = candidateCVRepository.findById(failure.getCvId())
                .orElseThrow(() -> new CustomException(ErrorCode.CV_NOT_FOUND));

        cv.setCvStatus(CVStatus.FAILED);
        cv.setErrorMessage(truncateErrorMessage(failure.getErrorMessage()));
        cv.setFailedAt(failure.getFailedAt());
        cv.setRetryCount(failure.getRetryCount());
        cv.setUpdatedAt(failure.getFailedAt());

        candidateCVRepository.save(cv);
        log.info("Updated CV {} status to FAILED", failure.getCvId());

        ProcessingBatch batch = processingBatchRepository.findByBatchId(failure.getBatchId())
                .orElseThrow(() -> new CustomException(ErrorCode.BATCH_NOT_FOUND));

        batch.setFailedCv(batch.getFailedCv() + 1);
        batch.setProcessedCv(batch.getProcessedCv() + 1);
        batch.setCreatedAt(failure.getFailedAt());

        // Check if batch is completed
        if (batch.getProcessedCv() >= batch.getTotalCv()) {
            batch.setStatus(BatchStatus.COMPLETED);
            batch.setCompletedAt(failure.getFailedAt());
            log.info("Batch {} completed: success={}, failed={}, total={}",
                    batch.getBatchId(), batch.getSuccessCv(), batch.getFailedCv(), batch.getTotalCv());
        }

        processingBatchRepository.save(batch);
        log.info("Updated batch {}: processed={}/{}, failed={}",
                batch.getBatchId(), batch.getProcessedCv(), batch.getTotalCv(), batch.getFailedCv());
    }

    // Helper method

    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return parts;
    }

    private void updateBatchCountersForRetry(String batchId) {
        ProcessingBatch batch = processingBatchRepository.findByBatchId(batchId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATCH_NOT_FOUND));

        batch.setFailedCv(batch.getFailedCv() - 1);
        batch.setProcessedCv(batch.getProcessedCv() - 1);

        if (batch.getStatus() == BatchStatus.COMPLETED) {
            batch.setStatus(BatchStatus.PROCESSING);
            batch.setCompletedAt(null);
        }

        processingBatchRepository.save(batch);
    }

    private void updateBatchCountersForManualScore(String batchId) {
        ProcessingBatch batch = processingBatchRepository.findByBatchId(batchId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATCH_NOT_FOUND));

        // Move from failed to success
        batch.setFailedCv(batch.getFailedCv() - 1);
        batch.setSuccessCv(batch.getSuccessCv() + 1);

        processingBatchRepository.save(batch);
    }

    public CandidateCVResponse toResponse(CandidateCV cv) {
        return CandidateCVResponse.builder()
                .cvId(cv.getId())
                .positionId(cv.getPosition().getId())
                .name(cv.getName())
                .email(cv.getEmail())
                .status(cv.getCvStatus())
                .errorMessage(cv.getErrorMessage())
                .failedAt(cv.getFailedAt())
                .retryCount(cv.getRetryCount())
                .canRetry(cv.getCvStatus() == CVStatus.FAILED)
                .updatedAt(cv.getUpdatedAt())
                .parsedAt(cv.getParsedAt())
                .scoredAt(cv.getScoredAt())
                .build();
    }

    private String truncateErrorMessage(String errorMessage) {
        if (errorMessage == null) {
            return "Unknown error";
        }
        final int MAX_LENGTH = 1000;
        if (errorMessage.length() <= MAX_LENGTH) {
            return errorMessage;
        }
        return errorMessage.substring(0, MAX_LENGTH) + "... [truncated]";
    }
}
