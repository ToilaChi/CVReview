package org.example.authservice.dto;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.authservice.models.Role;

@Data
@NoArgsConstructor
public class LoginResponse {
    private DataInfo data;
    private String message;

    public LoginResponse(String accessToken, String refreshToken, AccountInfo account, String message) {
        this.data = new DataInfo(accessToken, refreshToken, account);
        this.message = message;
    }

    public LoginResponse(DataInfo dataInfo, String loginSuccessful) {
        this.data = dataInfo;
        this.message = loginSuccessful;
    }

    @Data
    public static class DataInfo {
        private String accessToken;
        private String refreshToken;
        private AccountInfo account;

        public DataInfo(String accessToken, String refreshToken, AccountInfo account) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.account = account;
        }
    }

    @Getter
    public static class AccountInfo {
        private final String id;
        private final String name;
        private final Role role;

        public AccountInfo(String id, String name, Role role) {
            this.id = id;
            this.name = name;
            this.role = role;
        }
    }
}