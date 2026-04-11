package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.entity.ChatHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {

    /**
     * Lấy N messages gần nhất của session (sliding window).
     * Dùng ORDER BY DESC + LIMIT để chỉ lấy 20 rows, sau đó đảo lại ở service layer.
     */
    @Query("SELECT h FROM ChatHistory h WHERE h.sessionId = :sessionId ORDER BY h.createdAt DESC")
    List<ChatHistory> findTopNBySessionIdOrderByCreatedAtDesc(
            @Param("sessionId") String sessionId,
            Pageable pageable);

    /** Lấy toàn bộ history của session theo thứ tự thời gian — dùng cho FE xem lịch sử đầy đủ. */
    List<ChatHistory> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
