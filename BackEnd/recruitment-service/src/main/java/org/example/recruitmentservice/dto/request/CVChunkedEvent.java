package org.example.recruitmentservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CVChunkedEvent {
    private Integer cvId;
    private String candidateId;
    private String hrId;
    private String position;
    private List<ChunkPayload> chunks;
    private Integer totalChunks;
    private Integer totalTokens;
}