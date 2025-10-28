package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CandidateCVRepository extends JpaRepository<CandidateCV, Integer> {
    Page<CandidateCV> findByPositionId(int positionId, Pageable pageable);
    List<CandidateCV> findByPositionIdAndCvStatus(int positionId, CVStatus cvStatus);
    List<CandidateCV> findByPositionIdAndCvStatusAndBatchId(int positionId, CVStatus cvStatus, String batchId);
    List<CandidateCV> findByBatchIdAndCvStatus(String batchId, CVStatus cvStatus);
}
