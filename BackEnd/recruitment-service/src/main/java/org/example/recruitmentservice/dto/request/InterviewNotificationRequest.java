package org.example.recruitmentservice.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Payload gửi email phỏng vấn từ HR chatbot.
 * emailType: INTERVIEW_INVITE | OFFER_LETTER | REJECTION
 * interviewDate: ISO format string, nullable (không cần cho OFFER_LETTER và REJECTION)
 */
@Getter
@NoArgsConstructor
public class InterviewNotificationRequest {
    private String candidateId;
    private String candidateEmail;
    private String candidateName;
    private Integer positionId;
    private String positionName;
    private String emailType;
    private String interviewDate;
    private String customMessage;
    private String sessionId;
}
