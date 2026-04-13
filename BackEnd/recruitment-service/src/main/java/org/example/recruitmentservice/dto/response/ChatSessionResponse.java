package org.example.recruitmentservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import org.example.recruitmentservice.models.enums.ChatMode;
import org.example.recruitmentservice.models.enums.ChatbotType;

import java.time.LocalDateTime;

/** Response cho API tạo session và danh sách sessions của user. */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatSessionResponse {
    private String sessionId;
    private String userId;
    private ChatbotType chatbotType;
    private Integer positionId;
    private ChatMode mode;
    private LocalDateTime createdAt;
    private LocalDateTime lastActiveAt;
}
