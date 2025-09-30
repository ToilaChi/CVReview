package org.example.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final String expectedSubject = "CV Review";

    public JwtUtil(@Value("${jwt.secret}") String secret) {
        if (secret.length() < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 characters long");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Claims extractAllClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            throw new JwtException("Token không hợp lệ: " + e.getMessage());
        }
    }

    public String extractPhone(String token) {
        Claims claims = extractAllClaims(token);

        // Validate subject
        if (!expectedSubject.equals(claims.getSubject())) {
            throw new JwtException("Token không hợp lệ: sai subject");
        }

        // Extract and validate phone
        String phone = claims.get("Phone", String.class);
        if (phone == null || phone.isEmpty()) {
            throw new JwtException("Token không hợp lệ: thiếu thông tin Phone");
        }

        return phone;
    }

    public String extractRole(String token) {
        Claims claims = extractAllClaims(token);

        String role = claims.get("Role", String.class);
        if (role == null || role.isEmpty()) {
            throw new JwtException("Token không hợp lệ: thiếu thông tin role");
        }

        return role;
    }

    public boolean validateToken(String token) {
        try {
            Claims claims = extractAllClaims(token);

            // Check subject
            if (!expectedSubject.equals(claims.getSubject())) {
                System.out.println("Invalid subject in token");
                return false;
            }

            // Check expiration
            Date expiration = claims.getExpiration();
            if (expiration == null || expiration.before(new Date())) {
                System.out.println("Token expired");
                return false;
            }

            // Check required claims
            if (claims.get("Phone", String.class) == null ||
                    claims.get("Role", String.class) == null) {
                System.out.println("Missing required claims");
                return false;
            }

            return true;
        } catch (Exception e) {
            System.out.println("Token validation error: " + e.getMessage());
            return false;
        }
    }

    public String extractSubject(String token) {
        return extractAllClaims(token).getSubject();
    }

    public Date extractExpiration(String token) {
        return extractAllClaims(token).getExpiration();
    }
}