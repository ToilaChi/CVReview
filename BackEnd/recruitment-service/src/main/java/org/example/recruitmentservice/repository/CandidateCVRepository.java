package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CandidateCVRepository extends JpaRepository<CandidateCV, Integer> {
    Page<CandidateCV> findByPositionId(int positionId, Pageable pageable);

    @Query("SELECT c FROM CandidateCV c WHERE c.position.id = :positionId")
    List<CandidateCV> findListCVsByPositionId(@Param("positionId") int positionId);

    Page<CandidateCV> findByPositionIdAndCvStatusIn(int positionId, List<CVStatus> statuses, Pageable pageable);
    List<CandidateCV> findByPositionIdAndCvStatus(int positionId, CVStatus cvStatus);
    List<CandidateCV> findByPositionIdAndCvStatusAndBatchId(int positionId, CVStatus cvStatus, String batchId);
    List<CandidateCV> findByBatchIdAndCvStatus(String batchId, CVStatus cvStatus);
    long countByBatchIdAndCvStatus(String batchId, CVStatus cvStatus);
    int countByPositionId(int positionId);
    
    @Query("SELECT COUNT(c) FROM CandidateCV c WHERE c.updatedAt >= :date")
    long countTotalCVsAfterDate(@Param("date") java.time.LocalDateTime date);

    @Query("SELECT COUNT(c) FROM CandidateCV c WHERE c.cvStatus = :status AND c.updatedAt >= :date")
    long countByCvStatusAndDateAfter(@Param("status") CVStatus status, @Param("date") java.time.LocalDateTime date);
    
    CandidateCV findByCandidateId(String candidateId);

    /**
     * Load CandidateCV cùng Position trong 1 query → tránh LazyInitializationException
     * khi truy cập cv.getPosition() bên ngoài Hibernate session.
     */
    @Query("SELECT c FROM CandidateCV c LEFT JOIN FETCH c.position WHERE c.id = :id")
    Optional<CandidateCV> findByIdWithPosition(@Param("id") int id);

    /**
     * GC Job: Fetch only FAILED CVs that still have a Drive file pending deletion.
     * driveFileId != null ensures we don't attempt deleting already-cleaned records.
     */
    @Query("SELECT c FROM CandidateCV c WHERE c.cvStatus = 'FAILED' AND c.driveFileId IS NOT NULL AND c.deletedAt IS NULL")
    List<CandidateCV> findFailedCVsPendingCleanup();

    @Query(value = "SELECT c FROM CandidateCV c WHERE " +
           "(:sourceType IS NULL OR c.sourceType = :sourceType) AND " +
           "(:status IS NULL OR c.cvStatus = :status)")
    Page<org.example.recruitmentservice.dto.response.AdminCvSummaryDto> findAdminCvList(
            @Param("sourceType") org.example.recruitmentservice.models.enums.SourceType sourceType,
            @Param("status") CVStatus status,
            Pageable pageable);
}
