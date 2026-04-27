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

    /** ID của CandidateCV row trong MySQL — làm point ID trong Qdrant. */
    private Integer cvId;

    /** Toàn văn Markdown của CV đã parse — dùng để tạo embedding vector. */
    private String cvText;

    /** Metadata được extract bởi Gemini — được đính kèm vào Qdrant payload. */
    private CVMetadata metadata;

    /** ID của Position nếu là HR-sourced CV, null nếu là Master CV của Candidate. */
    private Integer positionId;

    private String batchId;
}
