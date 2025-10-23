package org.example.recruitmentservice.models.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.recruitmentservice.models.enums.BatchStatus;
import org.example.recruitmentservice.models.enums.BatchType;

import java.time.LocalDateTime;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "processing_batch")
@Builder
public class ProcessingBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, unique = true)
    private int id;

    @Column(nullable = false, unique = true)
    private String batchId;

    @Column(nullable = false)
    private Integer positionId;

    @Column(nullable = false)
    private Integer totalCv;

    @Column(nullable = false)
    private Integer processedCv = 0;

    @Column
    private Integer successCv;

    @Column Integer failedCv;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BatchStatus status = BatchStatus.PROCESSING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BatchType type;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime completedAt;
}
