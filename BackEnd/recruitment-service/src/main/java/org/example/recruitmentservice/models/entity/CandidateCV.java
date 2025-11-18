package org.example.recruitmentservice.models.entity;

import jakarta.persistence.*;
import lombok.*;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.models.enums.SourceType;

import java.time.LocalDateTime;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "candidate_cv")
public class CandidateCV {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, unique = true)
    private int id;

    @Column
    private String candidateId;

    @Column
    private String hrId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id")
    private Positions position;

    @Column
    @Enumerated(EnumType.STRING)
    private SourceType sourceType;

    @OneToOne(mappedBy = "candidateCV", cascade = CascadeType.ALL)
    private CVAnalysis analysis;

    @Column
    private String email;

    @Column
    private String name;

    @Column
    private String cvPath;

    @Column
    private String driveFileId;

    @Column
    private String driveFileUrl;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String cvContent;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    @Enumerated(EnumType.STRING)
    private CVStatus cvStatus;

    @Column
    private LocalDateTime parsedAt;

    @Column
    private String batchId;

    @Column
    private LocalDateTime scoredAt;

    @Column
    private LocalDateTime failedAt;

    @Column
    private Integer retryCount;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
}
