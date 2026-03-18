package org.example.authservice.services;

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
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private RefreshTokenService refreshTokenService;

    public ApiResponse<LoginData> login(LoginRequest loginRequest) {
        try {
            // Validate input
            if (loginRequest.getPhone() == null || loginRequest.getPhone().trim().isEmpty()) {
                return new ApiResponse<>(ErrorCode.MISSING_REQUIRED_FIELD.getCode(),
                        "Phone is required", null);
            }
            if (loginRequest.getPassword() == null || loginRequest.getPassword().trim().isEmpty()) {
                return new ApiResponse<>(ErrorCode.MISSING_REQUIRED_FIELD.getCode(),
                        "Password is required", null);
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

            // Upgrade password hash async — không block login
            if (needsPasswordUpgrade(user)) {
                upgradePasswordAsync(user, loginRequest.getPassword());
            }

            // Tạo token — chỉ INSERT, không DELETE → không deadlock
            String accessToken = jwtUtil.generateAccessToken(
                    user.getId(), user.getPhone(), user.getRole());
            RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);

            if (accessToken == null) {
                return new ApiResponse<>(ErrorCode.JWT_GENERATION_FAILED.getCode(),
                        ErrorCode.JWT_GENERATION_FAILED.getMessage(), null);
            }

            LoginData.AccountInfo accountInfo = new LoginData.AccountInfo(
                    user.getId(),
                    user.getEmail(),
                    user.getName(),
                    user.getPhone(),
                    user.getRole(),
                    user.getCreatedAt());

            LoginData loginData = new LoginData(
                    accessToken,
                    refreshToken.getToken(),
                    accountInfo);

            return new ApiResponse<>(ErrorCode.SUCCESS.getCode(),
                    "Welcome to CV Review System", loginData);

        } catch (Exception e) {
            System.err.println("Login failed: " + e.getMessage());
            e.printStackTrace();
            return new ApiResponse<>(ErrorCode.UNAUTHORIZED.getCode(),
                    "Login failed: " + e.getMessage(), null);
        }
    }

    public ApiResponse<LogoutData> logout(String userId, LogoutRequest logoutRequest) {
        try {
            if (userId == null || userId.trim().isEmpty()) {
                return new ApiResponse<>(ErrorCode.MISSING_REQUIRED_FIELD.getCode(),
                        "User ID is required", null);
            }

            // Xóa đúng refresh token của thiết bị này
            if (logoutRequest.getRefreshToken() != null
                    && !logoutRequest.getRefreshToken().trim().isEmpty()) {
                try {
                    refreshTokenService.findValidateAndDelete(
                            logoutRequest.getRefreshToken(), userId);
                } catch (RuntimeException e) {
                    if (e.getMessage().equals(ErrorCode.FORBIDDEN.getMessage())) {
                        return new ApiResponse<>(ErrorCode.FORBIDDEN.getCode(),
                                "Refresh token does not belong to this user", null);
                    }
                    // Token không tồn tại hoặc đã bị xóa → vẫn coi là logout thành công
                    System.out.println("Refresh token not found or already deleted: "
                            + e.getMessage());
                }
            }

            return new ApiResponse<>(ErrorCode.SUCCESS.getCode(),
                    "Logout successful", new LogoutData("Goodbye"));

        } catch (Exception e) {
            System.err.println("Logout error for user " + userId + ": " + e.getMessage());
            return new ApiResponse<>(ErrorCode.UNAUTHORIZED.getCode(),
                    "Logout failed: " + e.getMessage(), null);
        }
    }

    @Transactional
    public ApiResponse<Userdata> getUserDetail(String userId, String token) {
        try {
            if (userId == null || userId.trim().isEmpty()) {
                return new ApiResponse<>(ErrorCode.MISSING_REQUIRED_FIELD.getCode(),
                        "User ID is required", null);
            }

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
                    foundUser.getCreatedAt());

            Userdata userdata = new Userdata();
            userdata.setAccessToken(token);
            userdata.setAccount(userInfo);

            return new ApiResponse<>(ErrorCode.SUCCESS.getCode(),
                    "User detail fetched successfully", userdata);

        } catch (Exception e) {
            System.err.println("Failed to get user detail: " + e.getMessage());
            e.printStackTrace();
            return new ApiResponse<>(ErrorCode.USER_NOT_FOUND.getCode(),
                    "User not found", null);
        }
    }

    // ─── Helper methods ───────────────────────────────────────────────────────

    private boolean isPasswordValid(Users user, String rawPassword) {
        String pwd = user.getPassword();
        if (pwd.startsWith("$2a$") || pwd.startsWith("$2b$") || pwd.startsWith("$2y$")) {
            return passwordEncoder.matches(rawPassword, pwd);
        }
        return pwd.equals(rawPassword);
    }

    private boolean needsPasswordUpgrade(Users user) {
        String pwd = user.getPassword();
        return !pwd.startsWith("$2a$") && !pwd.startsWith("$2b$") && !pwd.startsWith("$2y$");
    }

    @Async
    @Transactional
    public void upgradePasswordAsync(Users user, String rawPassword) {
        user.setPassword(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
    }
}