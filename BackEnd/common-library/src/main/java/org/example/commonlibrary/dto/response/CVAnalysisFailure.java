package org.example.commonlibrary.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CVAnalysisFailure {
    private Integer cvId;
    private String batchId;
    private Integer positionId;
    private String errorMessage;
    private LocalDateTime failedAt;
    private Integer retryCount;
}
