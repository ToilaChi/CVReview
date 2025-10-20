package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.entity.ProcessingBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProcessingBatchRepository extends JpaRepository<ProcessingBatch, Long> {
    Optional<ProcessingBatch> findByBatchId(String batchId);
}

