package org.example.recruitmentservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.recruitmentservice.models.enums.MatchStatus;

import java.io.Serializable;

/**
 * Reply event từ embedding-service sau khi upsert Qdrant hoàn tất.
 * Được publish vào cv.embed.reply.queue để EmbedReplyListener cập nhật trạng
 * thái DB.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbedReplyEvent implements Serializable {

    private Integer cvId;
    private String batchId;
    private boolean success;
    private String errorMessage;

    private Integer technicalScore;
    private Integer experienceScore;
    private MatchStatus overallStatus;
}
