package org.example.authservice.services;

import jakarta.transaction.Transactional;
import org.example.authservice.dto.LoginRequest;
import org.example.authservice.dto.LoginResponse;
import org.example.authservice.dto.LogoutRequest;
import org.example.authservice.dto.LogoutResponse;
import org.example.authservice.models.RefreshToken;
import org.example.authservice.models.Users;
import org.example.authservice.repository.UserRepository;
import org.example.authservice.security.JwtUtil;
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
    public LoginResponse login(LoginRequest loginRequest) {
        try {
            Users phone = userRepository.findByPhone(loginRequest.getPhone());
            if(phone == null) {
                return new LoginResponse(null, "Số điện thoại sai. Vui lòng nhập lại!!!");
            }

            if(!isPasswordValid(phone, loginRequest.getPassword())) {
                return new LoginResponse(null, "Mật khẩu không đúng!!!");
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

                if(accessToken == null) {
                    System.err.println("accessToken is null");
                    throw new Exception("Failed to generate access token");
                }

                LoginResponse.AccountInfo accountInfo = new LoginResponse.AccountInfo(
                        phone.getId(),
                        phone.getName(),
                        phone.getRole()
                );

                LoginResponse.DataInfo dataInfo = new LoginResponse.DataInfo(
                        accessToken,
                        refreshTokenString,
                        accountInfo
                );

                return new LoginResponse(dataInfo, "Đăng nhập thành công!!!");
            }
            else {
                System.out.println("Lỗi mật khẩu!!!");
                throw new RuntimeException("Lỗi mật khẩu!!!");
            }
        }
        catch (Exception e) {
            System.err.println("Đăng nhập lỗi: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Đăng nhập thất bại: " + e.getMessage(), e);
        }
    }

    @Transactional
    public LogoutResponse logout(LogoutRequest logoutRequest) {
        try {
            if(logoutRequest.getRefreshToken() != null) {
                RefreshToken refreshToken = refreshTokenService.findByToken(logoutRequest.getRefreshToken());

                String accessToken = logoutRequest.getAccessToken();
                if (accessToken != null && !accessToken.isEmpty()) {
                    tokenBlackListService.addBlacklistToken(accessToken);
                }
                refreshTokenService.deleteByToken(refreshToken.getToken());
                return new LogoutResponse("Logout thành công!!!");
            }
            return new LogoutResponse("Refresh token không tìm thấy");
        }
        catch (Exception e) {
            return new LogoutResponse("Logout thất bại: " + e.getMessage());
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
