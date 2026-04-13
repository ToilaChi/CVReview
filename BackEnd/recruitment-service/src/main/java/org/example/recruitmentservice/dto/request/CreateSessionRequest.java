package org.example.recruitmentservice.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.recruitmentservice.models.enums.ChatMode;
import org.example.recruitmentservice.models.enums.ChatbotType;

/**
 * Payload để chatbot-service yêu cầu tạo một chat session mới.
 * positionId và mode chỉ bắt buộc với HR chatbot.
 */
@Getter
@NoArgsConstructor
public class CreateSessionRequest {
    private String userId;
    private ChatbotType chatbotType;
    private Integer positionId;
    private ChatMode mode;
}
