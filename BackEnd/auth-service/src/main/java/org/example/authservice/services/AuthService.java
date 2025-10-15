package org.example.authservice.services;

import jakarta.transaction.Transactional;
import org.example.authservice.dto.request.LoginRequest;
import org.example.authservice.dto.response.LoginData;
import org.example.authservice.dto.request.LogoutRequest;
import org.example.authservice.dto.response.LogoutData;
import org.example.authservice.models.RefreshToken;
import org.example.authservice.models.Users;
import org.example.authservice.repository.UserRepository;
import org.example.authservice.security.JwtUtil;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenBlackListService tokenBlackListService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Transactional
    public ApiResponse<LoginData> login(LoginRequest loginRequest) {
        try {
            if (loginRequest.getPhone() == null || loginRequest.getPhone().trim().isEmpty()) {
                return new ApiResponse<>(ErrorCode.INVALID_CREDENTIALS.getCode(), "Phone is required", null);
            }

            if (loginRequest.getPassword() == null || loginRequest.getPassword().trim().isEmpty()) {
                return new ApiResponse<>(ErrorCode.INVALID_CREDENTIALS.getCode(), "Password is required", null);
            }

            Users phone = userRepository.findByPhone(loginRequest.getPhone());
            if (phone == null) {
                return new ApiResponse<>(ErrorCode.USER_NOT_FOUND.getCode(), "Phone is incorrect. Please enter again", null);
            }

            if (!isPasswordValid(phone, loginRequest.getPassword())) {
                return new ApiResponse<>(ErrorCode.INVALID_CREDENTIALS.getCode(), "Password is incorrect", null);
            }

            if(isPasswordValid(phone, loginRequest.getPassword())) {
                if(!phone.getPassword().startsWith("$2a$")
                        && !phone.getPassword().startsWith("$2b$")
                        && !phone.getPassword().startsWith("$2y$")) {
                    System.out.println("Updating password with encryption");
                    updatePasswordWithEncryption(phone, loginRequest.getPassword());
                }
                // Create access token
                String accessToken = jwtUtil.generateAccessToken(phone.getPhone(), phone.getRole());

                // Create refresh token
                RefreshToken refreshToken = refreshTokenService.createRefreshToken(phone);
                String refreshTokenString = refreshToken.getToken();

                if (accessToken == null) {
                    System.err.println("accessToken is null");
                    return new ApiResponse<>(ErrorCode.UNAUTHORIZED.getCode(), "Failed to generate access token", null);
                }

                LoginData.AccountInfo accountInfo = new LoginData.AccountInfo(
                        phone.getId(),
                        phone.getName(),
                        phone.getRole()
                );

                LoginData loginData = new LoginData(
                        accessToken,
                        refreshTokenString,
                        accountInfo
                );

                return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Welcome to CV Review System", loginData);
            }
            else {
                System.out.println("Error password");
                throw new RuntimeException("Error password");
            }
        }
        catch (Exception e) {
            System.err.println("Login failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Login failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public ApiResponse<LogoutData> logout(LogoutRequest logoutRequest) {
        try {
            if (logoutRequest.getRefreshToken() != null) {
                RefreshToken refreshToken = refreshTokenService.findByToken(logoutRequest.getRefreshToken());

                String accessToken = logoutRequest.getAccessToken();
                if (accessToken != null && !accessToken.isEmpty()) {
                    tokenBlackListService.addBlacklistToken(accessToken);
                }

                refreshTokenService.deleteByToken(refreshToken.getToken());
                return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Logout successful", new LogoutData("Goodbye"));
            }

            return new ApiResponse<>(400, "Refresh token is not found", new LogoutData("Invalid request"));
        } catch (Exception e) {
            return new ApiResponse<>(500, "Logout failed", new LogoutData(e.getMessage()));
        }
    }


    private boolean isPasswordValid(Users user, String rawPassword) {
        if (user.getPassword().startsWith("$2a$") ||
                user.getPassword().startsWith("$2b$") ||
                user.getPassword().startsWith("$2y$")) {
            // Nếu password đã được mã hóa, so sánh bằng passwordEncoder
            return passwordEncoder.matches(rawPassword, user.getPassword());
        } else {
            // Nếu password chưa mã hóa, so sánh trực tiếp
            return user.getPassword().equals(rawPassword);
        }
    }

    private void updatePasswordWithEncryption(Users user, String rawPassword) {
        user.setPassword(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
    }
}
