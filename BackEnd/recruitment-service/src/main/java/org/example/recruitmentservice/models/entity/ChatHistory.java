package org.example.recruitmentservice.models.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.recruitmentservice.models.enums.ChatRole;

import java.time.LocalDateTime;

/**
 * Lưu từng turn của cuộc hội thoại (mỗi message = 1 row).
 * Sliding window: BE luôn chỉ lấy 20 rows gần nhất để build LLM context.
 */
@Entity
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "chat_history", indexes = {
        @Index(name = "idx_chat_history_session_id", columnList = "session_id")
})
public class ChatHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    private Long id;

    @Column(name = "session_id", length = 36, nullable = false)
    private String sessionId;

    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    private ChatRole role;

    @Lob
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * JSON log cho các function calls trong turn này.
     * Ví dụ: {"name": "finalize_application", "args": {...}, "result": "success"}
     * NULL nếu turn không có function call.
     */
    @Column(name = "function_call", columnDefinition = "JSON")
    private String functionCall;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
