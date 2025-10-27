package org.example.commonlibrary.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CVAnalysisResult {
    private Integer cvId;
    private Integer score;
    private String feedback;
    private List<String> skillMatch;
    private List<String> skillMiss;
    private LocalDateTime analyzedAt;
    private String batchId;
}
