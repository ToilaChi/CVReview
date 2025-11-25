package org.example.recruitmentservice.services.metadata.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder(toBuilder = true)
public class CVMetadata {
    private List<String> skills;
    private Integer experienceYears;
    private String seniorityLevel;
    private List<String> companies;
    private List<String> degrees;
    private List<String> dateRanges;
    private String documentSummary;

    // Helper method
    public static CVMetadata empty() {
        return CVMetadata.builder()
                .skills(List.of())
                .companies(List.of())
                .degrees(List.of())
                .dateRanges(List.of())
                .documentSummary("")
                .build();
    }
}
