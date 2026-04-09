package org.example.recruitmentservice.dto.response;

import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.models.enums.SourceType;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;

public interface AdminCvSummaryDto {
    int getId();
    String getName();
    String getEmail();
    String getCandidateId();
    String getHrId();
    
    @Value("#{target.position != null ? target.position.id : null}")
    Integer getPositionId();

    SourceType getSourceType();
    CVStatus getCvStatus();
    LocalDateTime getUpdatedAt();
    LocalDateTime getParsedAt();
    LocalDateTime getScoredAt();
    LocalDateTime getFailedAt();
    String getCvPath();
}
