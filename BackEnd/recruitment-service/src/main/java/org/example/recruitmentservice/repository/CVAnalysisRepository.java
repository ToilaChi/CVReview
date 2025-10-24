package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.entity.CVAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CVAnalysisRepository extends JpaRepository<CVAnalysis, Integer> {
    Optional<CVAnalysis> findByCandidateCV_Id(Integer cvId);
}
