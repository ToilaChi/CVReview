package org.example.authservice.services;

import jakarta.transaction.Transactional;
import org.example.authservice.dto.request.LoginRequest;
import org.example.authservice.dto.response.LoginData;
import org.example.authservice.dto.request.LogoutRequest;
import org.example.authservice.dto.response.LogoutData;
import org.example.authservice.dto.response.Userdata;
import org.example.authservice.models.RefreshToken;
import org.example.authservice.models.Users;
import org.example.authservice.repository.UserRepository;
import org.example.authservice.security.JwtUtil;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

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
            // Validate input
            if (loginRequest.getPhone() == null || loginRequest.getPhone().trim().isEmpty()) {
                return new ApiResponse<>(ErrorCode.MISSING_REQUIRED_FIELD.getCode(), "Phone is required", null);
            }

            if (loginRequest.getPassword() == null || loginRequest.getPassword().trim().isEmpty()) {
                return new ApiResponse<>(ErrorCode.MISSING_REQUIRED_FIELD.getCode(), "Password is required", null);
            }

            Users user = userRepository.findByPhone(loginRequest.getPhone());
            if (user == null) {
                return new ApiResponse<>(ErrorCode.USER_NOT_FOUND.getCode(),
                        ErrorCode.USER_NOT_FOUND.getMessage(), null);
            }

            if (!isPasswordValid(user, loginRequest.getPassword())) {
                return new ApiResponse<>(ErrorCode.INVALID_CREDENTIALS.getCode(),
                        ErrorCode.INVALID_CREDENTIALS.getMessage(), null);
            }

            // Upgrade password encryption if needed
            if (!user.getPassword().startsWith("$2a$")
                    && !user.getPassword().startsWith("$2b$")
                    && !user.getPassword().startsWith("$2y$")) {
                System.out.println("Updating password with encryption");
                updatePasswordWithEncryption(user, loginRequest.getPassword());
            }

            // Generate tokens
            String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getPhone(), user.getRole());
            RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);

            if (accessToken == null) {
                return new ApiResponse<>(ErrorCode.JWT_GENERATION_FAILED.getCode(),
                        ErrorCode.JWT_GENERATION_FAILED.getMessage(), null);
            }

            // Build response
            LoginData.AccountInfo accountInfo = new LoginData.AccountInfo(
                    user.getId(),
                    user.getEmail(),
                    user.getName(),
                    user.getPhone(),
                    user.getRole(),
                    user.getCreatedAt()
            );

            LoginData loginData = new LoginData(
                    accessToken,
                    refreshToken.getToken(),
                    accountInfo
            );

            return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Welcome to CV Review System", loginData);

        } catch (Exception e) {
            System.err.println("Login failed: " + e.getMessage());
            e.printStackTrace();
            return new ApiResponse<>(ErrorCode.UNAUTHORIZED.getCode(), "Login failed: " + e.getMessage(), null);
        }
    }

    @Transactional
    public ApiResponse<LogoutData> logout(String userId, LogoutRequest logoutRequest) {
        try {
            // Validate userId
            if (userId == null || userId.trim().isEmpty()) {
                return new ApiResponse<>(ErrorCode.MISSING_REQUIRED_FIELD.getCode(),
                        "User ID is required", null);
            }

            // Blacklist access token if provided
            if (logoutRequest.getAccessToken() != null && !logoutRequest.getAccessToken().trim().isEmpty()) {
                tokenBlackListService.addBlacklistToken(logoutRequest.getAccessToken());
                System.out.println("Access token blacklisted for user: " + userId);
            }

            // Delete refresh token if provided
            if (logoutRequest.getRefreshToken() != null && !logoutRequest.getRefreshToken().trim().isEmpty()) {
                try {
                    RefreshToken refreshToken = refreshTokenService.findByToken(logoutRequest.getRefreshToken());

                    // Verify ownership
                    if (!refreshToken.getUser().getId().equals(userId)) {
                        return new ApiResponse<>(ErrorCode.FORBIDDEN.getCode(),
                                "Refresh token does not belong to this user", null);
                    }

                    refreshTokenService.deleteByToken(refreshToken.getToken());
                    System.out.println("Refresh token deleted for user: " + userId);

                } catch (RuntimeException e) {
                    // Refresh token not found or already expired - not critical for logout
                    System.out.println("Refresh token not found or already deleted: " + e.getMessage());
                }
            }

            return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Logout successful", new LogoutData("Goodbye"));

        } catch (Exception e) {
            System.err.println("Logout error for user " + userId + ": " + e.getMessage());
            return new ApiResponse<>(ErrorCode.UNAUTHORIZED.getCode(), "Logout failed: " + e.getMessage(), null);
        }
    }

    @Transactional
    public ApiResponse<Userdata> getUserDetail(String userId, String token) {
        try {
            // Validate userId
            if (userId == null || userId.trim().isEmpty()) {
                return new ApiResponse<>(ErrorCode.MISSING_REQUIRED_FIELD.getCode(),
                        "User ID is required", null);
            }

            // Tìm user từ database dựa trên userId
            Optional<Users> user = userRepository.findById(userId);
            if (user.isEmpty()) {
                return new ApiResponse<>(ErrorCode.USER_NOT_FOUND.getCode(),
                        ErrorCode.USER_NOT_FOUND.getMessage(), null);
            }
            Users foundUser = user.get();

            Userdata.UserInfo userInfo = new Userdata.UserInfo(
                    foundUser.getId(),
                    foundUser.getName(),
                    foundUser.getEmail(),
                    foundUser.getPhone(),
                    foundUser.getRole(),
                    foundUser.getCreatedAt()
            );

            Userdata userdata = new Userdata();
            userdata.setAccessToken(token);
            userdata.setAccount(userInfo);

            return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "User detail fetched successfully", userdata);

        } catch (Exception e) {
            System.err.println("Failed to get user detail: " + e.getMessage());
            e.printStackTrace();
            return new ApiResponse<>(ErrorCode.USER_NOT_FOUND.getCode(), "User not found", null);
        }
    }

    // HELPER METHOD
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
