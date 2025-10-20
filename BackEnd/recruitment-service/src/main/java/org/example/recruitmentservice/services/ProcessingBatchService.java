package org.example.recruitmentservice.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.models.entity.ProcessingBatch;
import org.example.recruitmentservice.models.enums.BatchStatus;
import org.example.recruitmentservice.models.enums.BatchType;
import org.example.recruitmentservice.repository.ProcessingBatchRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingBatchService {
    private final ProcessingBatchRepository batchRepository;

    public ProcessingBatch createBatch(String batchId, Integer positionId, int totalCv, BatchType type) {
        ProcessingBatch batch = new ProcessingBatch();
        batch.setBatchId(batchId);
        batch.setPositionId(positionId);
        batch.setTotalCv(totalCv);
        batch.setProcessedCv(0);
        batch.setType(type);
        batch.setStatus(BatchStatus.PROCESSING);
        batch.setCreatedAt(LocalDateTime.now());
        batchRepository.save(batch);
        return batch;
    }

    public void incrementProcessed(String batchId) {
        ProcessingBatch batch = batchRepository.findByBatchId(batchId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATCH_NOT_FOUND));

        batch.setProcessedCv(batch.getProcessedCv() + 1);

        if (batch.getProcessedCv() >= batch.getTotalCv()) {
            batch.setStatus(BatchStatus.COMPLETED);
            batch.setCompletedAt(LocalDateTime.now());
        }

        batchRepository.save(batch);
    }

    public ProcessingBatch getBatch(String batchId) {
        return batchRepository.findByBatchId(batchId)
                .orElseThrow(() -> new CustomException(ErrorCode.BATCH_NOT_FOUND));
    }
}
