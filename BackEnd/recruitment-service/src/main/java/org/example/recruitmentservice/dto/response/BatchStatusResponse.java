package org.example.recruitmentservice.dto.response;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchStatusResponse {
    private String batchId;
    private Integer processedCv;
    private Integer totalCv;
    private Integer successCv;
    private Integer failedCv;
    private List<Integer> failedCvIds;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
