package org.example.recruitmentservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.recruitmentservice.services.metadata.model.CVMetadata;

import java.io.Serializable;

/**
 * Payload được publish sang cv.embed.queue sau khi extraction thành công.
 * Chứa toàn bộ thông tin cần thiết để embedding-service upsert vào Qdrant
 * mà không cần gọi lại database.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CVExtractedEvent implements Serializable {
    private Integer cvId;
    private String cvText;
    private CVMetadata metadata;
    private Integer positionId;
    private String batchId;
}
