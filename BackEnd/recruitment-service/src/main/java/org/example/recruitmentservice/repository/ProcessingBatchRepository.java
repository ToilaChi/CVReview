package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.entity.ProcessingBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProcessingBatchRepository extends JpaRepository<ProcessingBatch, Integer> {
    // @Modifying
    // @Query("UPDATE ProcessingBatch b " +
    // "SET b.processedCv = b.processedCv + 1 " +
    // "WHERE b.batchId = :batchId")
    // void incrementProcessed(@Param("batchId") String batchId);

    @Query("SELECT b FROM ProcessingBatch b WHERE b.batchId = :batchId")
    Optional<ProcessingBatch> findByBatchId(@Param("batchId") String batchId);

    @Query(value = "SELECT AVG(TIMESTAMPDIFF(SECOND, created_at, completed_at)) FROM processing_batch WHERE status = 'COMPLETED' AND completed_at >= :date AND total_cv BETWEEN 1 AND 10", nativeQuery = true)
    Double getAverageTimeFor1To10CVs(@Param("date") java.time.LocalDateTime date);

    @Query(value = "SELECT AVG(TIMESTAMPDIFF(SECOND, created_at, completed_at)) FROM processing_batch WHERE status = 'COMPLETED' AND completed_at >= :date AND total_cv BETWEEN 11 AND 20", nativeQuery = true)
    Double getAverageTimeFor11To20CVs(@Param("date") java.time.LocalDateTime date);

    @Query(value = "SELECT AVG(TIMESTAMPDIFF(SECOND, created_at, completed_at)) FROM processing_batch WHERE status = 'COMPLETED' AND completed_at >= :date AND total_cv BETWEEN 21 AND 30", nativeQuery = true)
    Double getAverageTimeFor21To30CVs(@Param("date") java.time.LocalDateTime date);

    @Query(value = "SELECT AVG(TIMESTAMPDIFF(SECOND, created_at, completed_at)) FROM processing_batch WHERE status = 'COMPLETED' AND completed_at >= :date AND total_cv > 30", nativeQuery = true)
    Double getAverageTimeForMoreThan30CVs(@Param("date") java.time.LocalDateTime date);
}
