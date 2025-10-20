package org.example.recruitmentservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CVUploadEvent {
    private int cvId;
    private String filePath;
    private int positionId;
    private String batchId;

    public CVUploadEvent(int cvId, String filePath, int positionId) {
        this.cvId = cvId;
        this.filePath = filePath;
        this.positionId = positionId;
    }
}
