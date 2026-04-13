package org.example.recruitmentservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import org.example.recruitmentservice.models.enums.ChatRole;

import java.time.LocalDateTime;

/** Response cho mỗi message turn trong chat history. */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessageResponse {
    private Long id;
    private String sessionId;
    private ChatRole role;
    private String content;
    private String functionCall;
    private LocalDateTime createdAt;
}
