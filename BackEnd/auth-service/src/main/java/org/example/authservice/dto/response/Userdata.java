package org.example.authservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.authservice.models.Role;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Userdata {
    private String accessToken;
    private Userdata.UserInfo account;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class UserInfo {
        private String id;
        private String name;
        private String email;
        private String phone;
        private Role role;
        private LocalDateTime createdAt;
    }
}
