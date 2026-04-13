package org.example.recruitmentservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

/**
 * DTO trả về thông tin ứng viên đã ứng tuyển vào 1 position.
 * HR chatbot mode CANDIDATE dùng candidateId list này để filter Qdrant master CV vectors.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApplicationSummaryResponse {
    private String candidateId;
    private String candidateName;
    private String candidateEmail;
    private Integer appCvId;
    private Integer score;
    private String feedback;
    private String skillMatch;
    private String skillMiss;
}
