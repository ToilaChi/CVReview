package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.Positions;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PositionRepository extends JpaRepository<Positions, Integer> {
    Positions findById(int positionId);
    Optional<Positions> findByNameAndLanguageAndLevel(String name, String language, String level);
    @Query("SELECT p FROM Positions p " +
            "WHERE (:name IS NULL OR p.name = :name) " +
            "AND (:language IS NULL OR p.language = :language) " +
            "AND (:level IS NULL OR p.level = :level)")
    List<Positions> findByFilters(@Param("name") String name,
                                  @Param("language") String language,
                                  @Param("level") String level);

    @Query("SELECT p FROM Positions p " +
            "WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "   OR LOWER(p.language) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "   OR LOWER(p.level) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Positions> searchByKeyword(@Param("keyword") String keyword);
    @Query("SELECT p FROM Positions p JOIN p.candidateCVs c WHERE c.id = :cvId")
    Positions findByCandidateCVId(@Param("cvId") int cvId);
}
