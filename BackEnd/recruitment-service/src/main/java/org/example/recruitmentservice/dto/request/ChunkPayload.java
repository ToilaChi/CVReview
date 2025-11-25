package org.example.recruitmentservice.dto.request;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ChunkPayload {
    private String candidateId;
    private String hrId;
    private String position;
    private String section;
    private int chunkIndex;
    private String chunkText;
    private int words;
    private int tokensEstimate;

    private String email;
    private Integer cvId;
    private String cvStatus;
    private String sourceType;
    private LocalDateTime createdAt;


    private List<String> skills;
    private Integer experienceYears;
    private String seniorityLevel;
    private List<String> companies;
    private List<String> degrees;
    private List<String> dateRanges;
//
//    private String documentSummary;
//    private String sectionSummary;
//    private String chunkSummary;
}
