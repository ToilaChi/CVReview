package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.Positions;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PositionRepository extends JpaRepository<Positions, Integer> {
    Optional<Positions> findByNameAndLanguageAndLevel(String name, String language, String level);
}
