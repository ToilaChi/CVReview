package org.example.authservice.services;

import jakarta.transaction.Transactional;
import org.example.authservice.models.RefreshToken;
import org.example.authservice.models.Users;
import org.example.authservice.repository.RefreshTokenRepository;
import org.example.authservice.repository.UserRepository;
import org.example.authservice.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class RefreshTokenService {
    @Value("${jwt.refresh-expiration}")
    private long refreshDuration;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Transactional
    public RefreshToken createRefreshToken(Users user) {
        //Delete old refresh token
        refreshTokenRepository.deleteByUser(user);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);

        String refreshTokenString = jwtUtil.generateRefreshToken(user.getPhone(), user.getRole());
        refreshToken.setToken(refreshTokenString);

        refreshToken.setExpiresAt(Instant.now().plusSeconds(refreshDuration));
        return refreshTokenRepository.save(refreshToken);
    }

    public RefreshToken verifyExpiration(RefreshToken token) {
        if(token.getExpiresAt().compareTo(Instant.now()) < 0) {
            refreshTokenRepository.delete(token);
            throw new RuntimeException("Refresh token was expired. Please login again.");
        }
        return token;
    }

    @Transactional
    public void deleteByUser(Users user) {
        refreshTokenRepository.deleteByUser(user);
    }

    public RefreshToken findByToken(String token) {
        return refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Refresh token not found"));
    }

    @Transactional
    public void deleteByToken(String token) {
        refreshTokenRepository.deleteByToken(token);
    }
}
