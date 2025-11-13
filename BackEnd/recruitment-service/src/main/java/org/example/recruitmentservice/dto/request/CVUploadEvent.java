package org.example.recruitmentservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CVUploadEvent implements Serializable {
    private Integer cvId;
    private String filePath;
    private Integer positionId; // Có thể null cho CANDIDATE
    private String batchId;
}
