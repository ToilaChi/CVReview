package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.CVStatus;
import org.example.recruitmentservice.models.CandidateCV;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CandidateCVRepository extends JpaRepository<CandidateCV, Integer> {
    Page<CandidateCV> findByPositionId(int positionId, Pageable pageable);
    List<CandidateCV> findByPositionIdAndCvStatus(int position_id, CVStatus cvStatus);
}
