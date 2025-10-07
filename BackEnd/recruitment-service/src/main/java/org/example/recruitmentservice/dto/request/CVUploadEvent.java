package org.example.recruitmentservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CVUploadEvent {
    private int cvId;
    private String filePath;
    private int positionId;
}
