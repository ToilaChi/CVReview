package org.example.recruitmentservice.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.recruitmentservice.models.enums.ChatRole;

/**
 * Payload để chatbot-service lưu một message turn vào chat history.
 * functionCall là JSON string, nullable — chỉ điền khi turn có function call.
 */
@Getter
@NoArgsConstructor
public class SaveMessageRequest {
    private String sessionId;
    private ChatRole role;
    private String content;
    private String functionCall;
}
