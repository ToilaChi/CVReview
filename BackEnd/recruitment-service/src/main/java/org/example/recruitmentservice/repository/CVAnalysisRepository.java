package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.entity.CVAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CVAnalysisRepository extends JpaRepository<CVAnalysis, Integer> {

        Optional<CVAnalysis> findByCandidateCV_Id(Integer cvId);

        /**
         * Đếm số CVAnalysis records cho một vị trí và nguồn cụ thể.
         */
        @Query("SELECT COUNT(a) FROM CVAnalysis a WHERE a.candidateCV.position.id = :positionId AND a.candidateCV.sourceType = :sourceType")
        long countScoredByPositionIdAndSourceType(@Param("positionId") int positionId,
                        @Param("sourceType") org.example.recruitmentservice.models.enums.SourceType sourceType);

        /** Đếm số CV đạt ngưỡng điểm pass cho một vị trí và nguồn cụ thể. */
        @Query("SELECT COUNT(a) FROM CVAnalysis a WHERE a.candidateCV.position.id = :positionId AND a.candidateCV.sourceType = :sourceType AND a.technicalScore >= :threshold")
        long countPassedByPositionIdAndSourceType(@Param("positionId") int positionId,
                        @Param("sourceType") org.example.recruitmentservice.models.enums.SourceType sourceType,
                        @Param("threshold") int threshold);

        /** Lấy toàn bộ CVAnalysis của một list các CV ID để tránh N+1. */
        List<CVAnalysis> findByCandidateCV_IdIn(List<Integer> cvIds);
}
