package org.example.authservice.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "refresh_tokens", indexes = {
        // Index đơn trên token — dùng cho lookup khi validate/logout
        @Index(name = "idx_refresh_token_token", columnList = "token"),
        // Composite index trên (user_id, expires_at) — dùng cho cleanup job
        // và query "lấy token còn hạn của user"
        @Index(name = "idx_refresh_token_user_expires", columnList = "user_id, expires_at")
})
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 512)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = Instant.now();
    }
}