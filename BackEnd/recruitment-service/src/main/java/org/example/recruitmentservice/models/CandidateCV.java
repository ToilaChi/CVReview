package org.example.recruitmentservice.models;

import jakarta.persistence.*;
import lombok.*;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id", nullable = false)
    private Positions position;

    @Column
    private String email;

    @Column
    private String name;

    @Column(nullable = false)
    private String cvPath;

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
    private LocalDateTime scoredAt;
}
