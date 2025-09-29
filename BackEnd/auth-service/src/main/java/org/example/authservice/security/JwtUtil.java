package org.example.authservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.example.authservice.models.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {
    private final SecretKey secretKey;

    private final long expirationTime;
    private final long refreshTime;

    public JwtUtil(
            @Value("${jwt.secret}") String secretKey,
            @Value("${jwt.expiration}") long expirationTime,
            @Value("${jwt.refresh-expiration}") long refreshTime
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        this.expirationTime = expirationTime;
        this.refreshTime = refreshTime;
    }

    public String generateAccessToken(String phone, Role role) {
        return Jwts.builder()
                .subject("CV Review")
                .claim("Phone", phone)
                .claim("Role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    public String generateRefreshToken(String phone, Role role) {
        return Jwts.builder()
                .subject("CV Review")
                .claim("Phone", phone)
                .claim("Role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTime))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    public String validateTokenAndRetrieveSubject(String token) throws JwtException {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if(!"CV Review".equals(claims.getSubject())) {
            throw new JwtException("Token không hợp lệ!!!");
        }

        return claims.get("Phone", String.class);
    }

    public String extractRole(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claims.get("Role", String.class);
    }
}