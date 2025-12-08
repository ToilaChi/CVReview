package org.example.authservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.authservice.models.Role;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginData {
    private String accessToken;
    private String refreshToken;
    private AccountInfo account;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AccountInfo {
        private String id;
        private String name;
        private String email;
        private String phone;
        private Role role;
        private LocalDateTime createdAt;
    }
}
