package org.example.recruitmentservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CvTrafficResponse {
    private long totalCv;
    private long successCv;
    private long failedCv;
    private long processingCv;
    private int days;
}
