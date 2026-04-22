package org.example.recruitmentservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * DTO trả về trạng thái ứng tuyển của một candidate.
 * Candidate chatbot dùng để trả lời "Tôi đã apply chưa?" mà không cần chuyển hướng UI.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CandidateApplicationStatusResponse {
    private String candidateId;
    private List<ApplicationRecord> applications;

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApplicationRecord {
        private Integer positionId;
        private String positionName;
        private Integer score;
        private String status;
        private String feedback;
    }
}
