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
    public ApiResponse<LogoutData> logout(@RequestBody LogoutRequest logoutRequest) {
        return authService.logout(logoutRequest);
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<ApiResponse<RefreshTokenResponse>> refreshToken(
            @RequestBody RefreshTokenRequest refreshTokenRequest) {
        try {
            RefreshToken refreshToken = refreshTokenService.findByToken(refreshTokenRequest.getRefreshToken());
            refreshTokenService.verifyExpiration(refreshToken);

            // Create new access token
            Users user = refreshToken.getUser();
            String newAccessToken = jwtUtil.generateAccessToken(user.getId(), user.getPhone(), user.getRole());

            RefreshTokenResponse responseData =
                    new RefreshTokenResponse(refreshToken.getToken(), newAccessToken);

            ApiResponse<RefreshTokenResponse> apiResponse =
                    new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Token refreshed successfully", responseData);

            return ResponseEntity.ok(apiResponse);
        } catch (Exception e) {
            ApiResponse<RefreshTokenResponse> errorResponse =
                    new ApiResponse<>(400, "Failed to refresh token: " + e.getMessage(), null);

            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @GetMapping("/user-detail")
    public ApiResponse<Userdata> getUserDetail(
            @RequestHeader(name = "Authorization", required = false) String authHeader) {
        String refreshTokenString = authHeader.substring(7); // bỏ "Bearer "

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken(refreshTokenString);

        return authService.getUserDetail(refreshToken);
    }
}
