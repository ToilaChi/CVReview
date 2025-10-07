package org.example.recruitmentservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.recruitmentservice.models.CVStatus;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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

