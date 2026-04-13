package org.example.recruitmentservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/** Response sau khi finalize_application thành công — trả applicationCvId cho chatbot-service log. */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FinalizeApplicationResponse {
    private Integer applicationCvId;
    private String message;
    private LocalDateTime appliedAt;
}
