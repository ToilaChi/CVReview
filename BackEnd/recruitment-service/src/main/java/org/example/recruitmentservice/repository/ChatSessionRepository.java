package org.example.recruitmentservice.repository;

import org.example.recruitmentservice.models.entity.ChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    /** Lấy danh sách sessions của 1 user, sắp xếp theo lastActiveAt mới nhất — cho FE xem lịch sử. */
    Page<ChatSession> findByUserIdOrderByLastActiveAtDesc(String userId, Pageable pageable);

    Optional<ChatSession> findBySessionId(String sessionId);
}
