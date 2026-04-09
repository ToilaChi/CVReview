package org.example.recruitmentservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessingTimeResponse {
    private int days;
    private List<BucketTime> buckets;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class BucketTime {
        private String label; // "1-10 CVs", "11-20 CVs", "21-30 CVs", "> 30 CVs"
        private double averageTimeInSeconds;
    }
}
