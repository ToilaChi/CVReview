package org.example.authservice.repository;

import org.example.authservice.models.RefreshToken;
import org.example.authservice.models.Users;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // Lookup token khi validate hoặc logout
    Optional<RefreshToken> findByToken(String token);

    // Xóa 1 token cụ thể khi logout — atomic, không cần lock
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.token = :token")
    void deleteByToken(@Param("token") String token);

    // Xóa tất cả token của user — dùng khi force logout all devices
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.user = :user")
    void deleteByUser(@Param("user") Users user);

    // Đếm số token active của user — dùng để enforce giới hạn MAX_TOKENS_PER_USER
    @Query("SELECT COUNT(r) FROM RefreshToken r WHERE r.user = :user AND r.expiresAt > :now")
    long countActiveTokensByUser(@Param("user") Users user, @Param("now") Instant now);

    // Xoá các token cũ nhất vượt quá giới hạn bằng native query để tránh Race Condition
    // Query này xóa sạch các token thừa, giữ lại đúng `limit` token mới nhất của user.
    @Modifying
    @Query(value = "DELETE FROM refresh_tokens WHERE user_id = :userId AND id NOT IN " +
                   "(SELECT id FROM (SELECT id FROM refresh_tokens WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit) AS keep_tokens)", 
           nativeQuery = true)
    int deleteOldTokensForUser(@Param("userId") String userId, @Param("limit") int limit);

    // Cleanup job: xóa tất cả token đã hết hạn — chạy định kỳ
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :now")
    int deleteExpiredTokens(@Param("now") Instant now);
}