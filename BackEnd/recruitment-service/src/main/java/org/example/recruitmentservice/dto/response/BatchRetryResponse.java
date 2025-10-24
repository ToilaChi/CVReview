package org.example.recruitmentservice.dto.response;

import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchRetryResponse {
    private String batchId;
    private Integer totalRetried;
    private Integer failedToRetry;
    private List<Integer> retriedCvIds;
    private String message;
}
