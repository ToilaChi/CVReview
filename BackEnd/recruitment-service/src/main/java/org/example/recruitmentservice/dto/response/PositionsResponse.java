package org.example.recruitmentservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.example.recruitmentservice.models.entity.Positions;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public class PositionsResponse {
    private int id;
    private String name;
    private String language;
    private String level;
    private String positionName;
    private String jdPath;
    private String driveFileUrl;
    private Integer totalCVs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public PositionsResponse(Positions positions) {
        this.id = positions.getId();
        this.name = positions.getName();
        this.language = positions.getLanguage();
        this.level = positions.getLevel();
        this.jdPath = positions.getJdPath();
        this.driveFileUrl = positions.getDriveFileUrl();
        this.createdAt = positions.getCreatedAt();
        this.updatedAt = positions.getUpdatedAt();
    }
}
