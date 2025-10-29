package org.example.recruitmentservice.models.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "positions")
@Builder
public class Positions {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, unique = true)
    private int id;

    @OneToMany(mappedBy = "position", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CandidateCV> candidateCVs = new ArrayList<>();

    private String name;

    private String language;

    private String level;

    @Lob
    @Column(columnDefinition = "TEXT", nullable = false)
    private String jobDescription;

    @Column(nullable = false)
    private String jdPath;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
