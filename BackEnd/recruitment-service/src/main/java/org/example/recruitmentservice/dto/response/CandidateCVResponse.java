package org.example.recruitmentservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.example.recruitmentservice.models.CVStatus;

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
    private LocalDateTime updatedAt;
    private LocalDateTime parsedAt;
}

