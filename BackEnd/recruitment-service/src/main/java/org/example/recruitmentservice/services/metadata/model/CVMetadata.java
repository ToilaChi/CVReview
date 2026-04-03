package org.example.recruitmentservice.services.metadata.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CVMetadata {
    private List<String> skills;
    private Integer experienceYears;
    private String seniorityLevel;
    private List<String> companies;
    private List<String> degrees;
    private List<String> dateRanges;
    private String documentSummary;

    // Helper method - đảm bảo tất cả List fields được khởi tạo, không bao giờ null
    public static CVMetadata empty() {
        return CVMetadata.builder()
                .skills(List.of())
                .companies(List.of())
                .degrees(List.of())
                .dateRanges(List.of())
                .seniorityLevel("Unknown")
                .experienceYears(0)
                .documentSummary("")
                .build();
    }
}
