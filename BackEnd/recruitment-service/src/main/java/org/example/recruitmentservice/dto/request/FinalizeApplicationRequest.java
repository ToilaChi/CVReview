package org.example.recruitmentservice.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Payload finalize_application từ chatbot-service.
 * Chatbot đã tính score và feedback ở Python side, gửi sang để tạo Application CV + CVAnalysis.
 * score phải >= 70 (đã validate ở chatbot-service), nhưng recruitment-service cũng re-validate.
 */
@Getter
@NoArgsConstructor
public class FinalizeApplicationRequest {
    private String candidateId;
    private Integer positionId;
    private Integer score;
    private String feedback;
    private String skillMatch;
    private String skillMiss;
    private String sessionId;
}
