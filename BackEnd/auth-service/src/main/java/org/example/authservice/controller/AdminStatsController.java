package org.example.authservice.controller;

import org.example.authservice.dto.response.UserStatsResponse;
import org.example.authservice.models.Role;
import org.example.authservice.repository.UserRepository;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/stats")
public class AdminStatsController {

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/users")
    public ApiResponse<UserStatsResponse> getUserStats() {
        try {
            long totalUsers = userRepository.count();
            long totalHr = userRepository.countByRole(Role.HR);
            long totalCandidate = userRepository.countByRole(Role.CANDIDATE);

            UserStatsResponse stats = UserStatsResponse.builder()
                    .totalUsers(totalUsers)
                    .totalHr(totalHr)
                    .totalCandidate(totalCandidate)
                    .build();

            return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "User statistics retrieved successfully", stats);
        } catch (Exception e) {
            System.err.println("Failed to fetch user stats: " + e.getMessage());
            e.printStackTrace();
            return new ApiResponse<>(ErrorCode.INTERNAL_SERVER_ERROR.getCode(), "Failed to fetch user stats", null);
        }
    }
}
