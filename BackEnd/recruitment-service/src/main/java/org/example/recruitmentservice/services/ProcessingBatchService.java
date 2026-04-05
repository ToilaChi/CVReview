package org.example.recruitmentservice.services;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.dto.response.BatchStatusResponse;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.models.entity.ProcessingBatch;
import org.example.recruitmentservice.models.enums.BatchStatus;
import org.example.recruitmentservice.models.enums.BatchType;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.ProcessingBatchRepository;
import org.example.recruitmentservice.sse.SseEmitterRegistry;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingBatchService {
    private final ProcessingBatchRepository batchRepository;
    private final CandidateCVRepository candidateCVRepository;
    private final SseEmitterRegistry sseEmitterRegistry;

    public ProcessingBatch createBatch(String batchId, Integer positionId, int totalCv, BatchType type) {
        ProcessingBatch batch = new ProcessingBatch();
        batch.setBatchId(batchId);
        batch.setPositionId(positionId);
        batch.setTotalCv(totalCv);
        batch.setSuccessCv(0);
        batch.setFailedCv(0);
        batch.setStatus(BatchStatus.PROCESSING);
        batch.setType(type);
        batch.setCreatedAt(LocalDateTime.now());

        return batchRepository.save(batch);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementProcessed(String batchId, boolean isSuccess) {
        ProcessingBatch batch = batchRepository.findByBatchId(batchId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATCH_NOT_FOUND));

        // Fetch actual counts directly from database instead of cumulative increment
        long actualSuccess = candidateCVRepository.countByBatchIdAndCvStatus(batchId, CVStatus.PARSED);
        long actualFailed = candidateCVRepository.countByBatchIdAndCvStatus(batchId, CVStatus.FAILED);

        batch.setSuccessCv((int) actualSuccess);
        batch.setFailedCv((int) actualFailed);

        boolean isCompleted = batch.getProcessedCv() >= batch.getTotalCv();
        if (isCompleted) {
            batch.setStatus(BatchStatus.COMPLETED);
            batch.setCompletedAt(LocalDateTime.now());
            log.info("Batch {} completed: {}/{} processed, {} success, {} failed",
                    batchId, batch.getProcessedCv(), batch.getTotalCv(),
                    batch.getSuccessCv(), batch.getFailedCv());
        }

        batchRepository.save(batch);

        // Push live update to FE via SSE after DB is persisted
        BatchStatusResponse snapshot = buildStatusSnapshot(batch, batchId);
        sseEmitterRegistry.send(batchId, snapshot);
        if (isCompleted) {
            sseEmitterRegistry.complete(batchId);
        }
    }

    public ApiResponse<BatchStatusResponse> getBatchStatus(String batchId) {
        ProcessingBatch batch = batchRepository.findByBatchId(batchId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATCH_NOT_FOUND));

        BatchStatusResponse response = buildStatusSnapshot(batch, batchId);

        return new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                "Batch status retrieved successfully",
                response
        );
    }

    /**
     * Builds a BatchStatusResponse from the given batch entity.
     * Fetches failed CV IDs from the DB. Used by both the REST endpoint and SSE push.
     */
    private BatchStatusResponse buildStatusSnapshot(ProcessingBatch batch, String batchId) {
        List<Integer> failedCvIds = candidateCVRepository
                .findByBatchIdAndCvStatus(batchId, CVStatus.FAILED)
                .stream()
                .map(CandidateCV::getId)
                .collect(Collectors.toList());

        return BatchStatusResponse.builder()
                .batchId(batch.getBatchId())
                .processedCv(batch.getProcessedCv())
                .totalCv(batch.getTotalCv())
                .successCv(batch.getSuccessCv())
                .failedCv(batch.getFailedCv())
                .failedCvIds(failedCvIds)
                .progress(BigDecimal.valueOf(batch.getProgress())
                                    .setScale(2, RoundingMode.HALF_UP)
                                    .doubleValue())
                .pending(batch.getPendingCv())
                .status(batch.getStatus().name())
                .createdAt(batch.getCreatedAt())
                .completedAt(batch.getCompletedAt())
                .build();
    }
}
