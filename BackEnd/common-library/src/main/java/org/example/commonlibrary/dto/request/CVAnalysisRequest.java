package org.example.commonlibrary.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CVAnalysisRequest {
    private Integer cvId;
    private Integer positionId;
    private String cvText;
    private String jdText;
    private String batchId;
}
