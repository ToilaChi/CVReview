package org.example.authservice.repository;

import org.example.authservice.models.RefreshToken;
import org.example.authservice.models.Users;
import org.springframework.data.jpa.repository.JpaRepository;


import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    Optional<RefreshToken> findByToken(String token);
    void deleteByUser (Users user);
    void deleteByToken (String token);
}
