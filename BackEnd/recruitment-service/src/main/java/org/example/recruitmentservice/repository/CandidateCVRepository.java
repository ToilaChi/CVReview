package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.CandidateCV;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateCVRepository extends JpaRepository<CandidateCV, Integer> {
}
