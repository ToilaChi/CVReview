package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.entity.ProcessingBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProcessingBatchRepository extends JpaRepository<ProcessingBatch, Long> {
//    @Modifying
//    @Query("UPDATE ProcessingBatch b " +
//            "SET b.processedCv = b.processedCv + 1 " +
//            "WHERE b.batchId = :batchId")
//    void incrementProcessed(@Param("batchId") String batchId);

    @Query("SELECT b FROM ProcessingBatch b WHERE b.batchId = :batchId")
    Optional<ProcessingBatch> findByBatchId(@Param("batchId") String batchId);
}

