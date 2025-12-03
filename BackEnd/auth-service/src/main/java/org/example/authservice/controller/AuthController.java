package org.example.authservice.controller;

import org.example.authservice.dto.request.LoginRequest;
import org.example.authservice.dto.request.LogoutRequest;
import org.example.authservice.dto.request.RefreshTokenRequest;
import org.example.authservice.dto.response.LoginData;
import org.example.authservice.dto.response.LogoutData;
import org.example.authservice.dto.response.RefreshTokenResponse;
import org.example.authservice.dto.response.Userdata;
import org.example.authservice.models.RefreshToken;
import org.example.authservice.models.Users;
import org.example.authservice.security.JwtUtil;
import org.example.authservice.services.AuthService;
import org.example.authservice.services.RefreshTokenService;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final JwtUtil jwtUtil;

    public AuthController(AuthService authService, RefreshTokenService refreshTokenService, JwtUtil jwtUtil) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public ApiResponse<LoginData> login(@RequestBody LoginRequest loginRequest) {
        return authService.login(loginRequest);
    }

    @PostMapping("/logout")
    public ApiResponse<LogoutData> logout(
            @RequestHeader(value = "X-User-Id", required = true) String userId,
            @RequestHeader(value = "X-User-Phone", required = false) String userPhone,
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @RequestBody LogoutRequest logoutRequest) {

        // LogoutRequest đã có cả accessToken và refreshToken rồi
        return authService.logout(userId, logoutRequest);
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<ApiResponse<RefreshTokenResponse>> refreshToken(
            @RequestBody RefreshTokenRequest refreshTokenRequest) {
        try {
            RefreshToken refreshToken = refreshTokenService.findByToken(
                    refreshTokenRequest.getRefreshToken());
            refreshTokenService.verifyExpiration(refreshToken);

            Users user = refreshToken.getUser();
            String newAccessToken = jwtUtil.generateAccessToken(
                    user.getId(), user.getPhone(), user.getRole());

            RefreshTokenResponse responseData = new RefreshTokenResponse(
                    refreshToken.getToken(), newAccessToken);

            ApiResponse<RefreshTokenResponse> apiResponse = new ApiResponse<>(
                    ErrorCode.SUCCESS.getCode(),
                    ErrorCode.SUCCESS.getMessage(),
                    responseData);

            return ResponseEntity.ok(apiResponse);

        } catch (RuntimeException e) {
            // Phân loại lỗi cụ thể
            ErrorCode errorCode;
            if (e.getMessage().contains("expired")) {
                errorCode = ErrorCode.REFRESH_TOKEN_EXPIRED;
            } else if (e.getMessage().contains("not found")) {
                errorCode = ErrorCode.REFRESH_TOKEN_NOT_FOUND;
            } else {
                errorCode = ErrorCode.REFRESH_TOKEN_INVALID;
            }

            ApiResponse<RefreshTokenResponse> errorResponse = new ApiResponse<>(
                    errorCode.getCode(),
                    errorCode.getMessage(),
                    null);

            return ResponseEntity.status(errorCode.getHttpStatus()).body(errorResponse);
        }
    }

    @GetMapping("/user-detail")
    public ApiResponse<Userdata> getUserDetail(
            @RequestHeader(value = "X-User-Id", required = true) String userId,
            @RequestHeader(value = "X-User-Phone", required = false) String userPhone,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        return authService.getUserDetail(userId);
    }
}
