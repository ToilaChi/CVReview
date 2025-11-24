package org.example.recruitmentservice.dto.request;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
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
}
