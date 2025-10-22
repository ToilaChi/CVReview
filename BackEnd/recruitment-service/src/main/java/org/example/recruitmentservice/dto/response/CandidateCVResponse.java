package org.example.recruitmentservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.example.recruitmentservice.models.enums.CVStatus;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public class CandidateCVResponse {
    private int cvId;
    private int positionId;
    private String email;
    private String name;
    private String fileName;
    private String filePath;
    private CVStatus status;
    private String errorMessage;
    private LocalDateTime failedAt;
    private Integer retryCount;
    private Boolean canRetry;
    private LocalDateTime updatedAt;
    private LocalDateTime parsedAt;
    private LocalDateTime scoredAt;
}

