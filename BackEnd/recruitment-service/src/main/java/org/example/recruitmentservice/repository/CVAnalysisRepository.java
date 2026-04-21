package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.entity.CVAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CVAnalysisRepository extends JpaRepository<CVAnalysis, Integer> {

    Optional<CVAnalysis> findByCandidateCV_Id(Integer cvId);

    /** Đếm số CVAnalysis records cho một vị trí — tức là số CV đã được chấm điểm. */
    @Query("SELECT COUNT(a) FROM CVAnalysis a WHERE a.candidateCV.position.id = :positionId")
    long countScoredByPositionId(@Param("positionId") int positionId);

    /** Đếm số CV đạt ngưỡng điểm pass cho một vị trí. */
    @Query("SELECT COUNT(a) FROM CVAnalysis a WHERE a.candidateCV.position.id = :positionId AND a.score >= :threshold")
    long countPassedByPositionId(@Param("positionId") int positionId, @Param("threshold") int threshold);
}
